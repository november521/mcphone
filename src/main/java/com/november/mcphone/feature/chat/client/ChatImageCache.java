package com.november.mcphone.feature.chat.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.november.mcphone.core.client.ImageCodec;
import com.november.mcphone.feature.chat.ChatImage;
import com.november.mcphone.feature.chat.net.RequestChatImagePacket;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端这边的图片消息缓存：id → 显存里的那张贴图。
 *
 * 像素为什么是"看到了才去要"
 *
 * 消息里只有图片 id（见 ImageBody）。一个会话最多 100 条消息，其中可能有 20 张图，
 * 一进会话就全下载等于几百 KB 的突发流量，而玩家多半只看得见最后两三张。所以这里
 * 与相册是同一套办法：界面画到哪一张，才去要哪一张。
 *
 * 攒一批再要
 *
 * 服务端对拉取类的包有 500 毫秒限流，一次一个包地要，屏幕上三张图就要一秒半才凑齐。
 * 所以本类把这一帧"想要但还没有"的都记下来，攒够一批（至多 {@link RequestChatImagePacket#MAX_IDS} 张）
 * 一次发出去。
 *
 * 要了没回怎么办
 *
 * 隔 {@link #RETRY_AFTER_MS} 再要一次。包可能正好撞上限流被丢掉，而客户端无从知道——
 * 没有重试的话那张图就永远停在"加载中"。
 *
 * 线程
 *
 * 除后台解码外全在渲染线程。解码完回到渲染线程再上传贴图（GL 调用），与相册同一条路。
 */
public final class ChatImageCache {

    private ChatImageCache() {}

    /** 界面据此决定画什么：贴图、"加载中"、还是"已过期" */
    public enum Status {
        /** 还没有，正在要或即将去要 */
        LOADING,
        /** 已经在显存里了 */
        READY,
        /** 服务端说这张图没了（被上限挤掉了像素，或服主清过图片仓） */
        GONE,
        /** 收到了字节但解不开 */
        BROKEN
    }

    /**
     * 缓存条数上限。
     *
     * 一屏至多显示三四张，留 16 条是给上下翻用的余量。一张 384 长边的图占
     * 384×216×4 ≈ 330 KB 显存，另外还留着原始的 PNG 字节（至多 128 KB，供"保存到相册"用），
     * 16 条封顶约 7 MB——比相册那份（96 长边的缩略图）重得多，所以数目要小得多。
     */
    private static final int MAX_ENTRIES = 16;

    /** 要过之后多久没回音就再要一次 */
    private static final long RETRY_AFTER_MS = 6000L;

    /** 两次请求包之间至少隔这么久。服务端限流是 500 毫秒，这里留一点余量 */
    private static final long REQUEST_INTERVAL_MS = 600L;

    private static final class Entry {
        Status status = Status.LOADING;
        ImageCodec.Texture texture;

        /**
         * 原始的 PNG 字节，供「保存到相册」用。
         *
         * 留着而不是要用时再问服务端要一遍：字节已经付过一次流量了，而且贴图是解码放大过的
         * 像素，从它反推不回原文件。至多 128 KB 一张，随条目一起被 LRU 挤掉。
         */
        byte[] png;

        /** 0 表示还没要过 */
        long requestedAt;
    }

    /** 访问序：每次界面问到都会把它移到最新，被挤掉的必然是很久没显示的那几张 */
    private static final Map<UUID, Entry> ENTRIES =
            new LinkedHashMap<>(MAX_ENTRIES + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Entry> eldest) {
                    if (size() <= MAX_ENTRIES) return false;
                    ImageCodec.release(eldest.getValue().texture);
                    return true;
                }
            };

    private static long lastRequestMs;

    /**
     * 世代，每次 {@link #clear()} 递增。
     *
     * 退出会话时仍在解码的那几张，回来时世代已经变了，直接丢弃——否则它们会在刚清空的
     * 缓存里重新注册出几张没人回收的贴图。与相册那份同一个道理。
     */
    private static int generation;

    /**
     * 要这张图的贴图，顺带告诉缓存"这一帧它是可见的"。
     *
     * 没有就返回 null 并记下"想要"，由 {@link #flushRequests} 在本帧末尾统一去要。
     * 每帧调用是安全的：状态机挡着，不会重复发包。
     */
    public static ImageCodec.Texture get(UUID image) {
        if (image == null) return null;

        Entry entry = ENTRIES.get(image);   // 命中即刷新 LRU 位置
        if (entry == null) {
            ENTRIES.put(image, new Entry());
            return null;
        }
        return entry.status == Status.READY ? entry.texture : null;
    }

    /**
     * 这张图的原始 PNG 字节，没有则返回 null。只有「保存到相册」用得上。
     *
     * 留着字节而不是保存时再向服务端要一遍：这份流量已经付过了，而贴图是解码放大过的
     * 像素，从它反推不回原文件。
     */
    public static byte[] bytes(UUID image) {
        Entry entry = ENTRIES.get(image);
        return entry == null ? null : entry.png;
    }

    /** 这张图现在是什么状态。没问过的一律算"加载中"——界面下一帧就会去问 */
    public static Status status(UUID image) {
        Entry entry = ENTRIES.get(image);
        return entry == null ? Status.LOADING : entry.status;
    }

    /**
     * 把这一帧要的图凑一批发出去。由会话界面每帧调一次，peer 是当前会话的对端。
     *
     * 攒批与限流的理由见类注释。没有要的东西时一个包都不发。
     */
    public static void flushRequests(UUID peer) {
        if (peer == null || Minecraft.getInstance().getConnection() == null) return;

        long now = System.currentTimeMillis();
        if (now - lastRequestMs < REQUEST_INTERVAL_MS) return;

        List<UUID> wanted = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : ENTRIES.entrySet()) {
            Entry entry = e.getValue();
            if (entry.status != Status.LOADING) continue;
            if (entry.requestedAt != 0 && now - entry.requestedAt < RETRY_AFTER_MS) continue;

            wanted.add(e.getKey());
            if (wanted.size() >= RequestChatImagePacket.MAX_IDS) break;
        }
        if (wanted.isEmpty()) return;

        for (UUID id : wanted) ENTRIES.get(id).requestedAt = now;
        lastRequestMs = now;
        PacketDistributor.sendToServer(new RequestChatImagePacket(peer, wanted));
    }

    /**
     * 服务端把像素发来了（空数组表示这张图没了）。由 MCphoneClient 装的监听器调用。
     *
     * 解码放后台线程：一张 384×216 的图解出来是八万多个像素，逐个转字节序，放渲染线程
     * 会看得见地卡一下。
     */
    public static void accept(UUID image, byte[] data) {
        Entry entry = ENTRIES.get(image);
        if (entry == null) {
            // 早就被挤出缓存了（玩家翻走了）。重新记一条，别把这份已经付过流量的字节扔掉
            entry = new Entry();
            ENTRIES.put(image, entry);
        }

        if (data.length == 0) {
            entry.status = Status.GONE;
            return;
        }
        entry.png = data;

        final int gen = generation;
        Util.backgroundExecutor().execute(() -> {
            NativeImage decoded = ImageCodec.decodeAndScale(data, ChatImage.MAX_SIDE);
            Minecraft.getInstance().execute(() -> install(image, gen, decoded));
        });
    }

    /**
     * 把自己刚发出去的那张图直接放进缓存。
     *
     * 服务端存的就是客户端发上去的那几个字节，一模一样。发件人靠回声显示自己那条消息，
     * 而回声里只有 id——不塞这一手的话，他会看着自己刚发的图转一圈"加载中"，
     * 再从服务器把自己刚上传的东西下回来。
     */
    public static void seed(UUID image, byte[] png) {
        Entry entry = ENTRIES.computeIfAbsent(image, k -> new Entry());
        entry.png = png;
        if (entry.status == Status.READY) return;

        final int gen = generation;
        Util.backgroundExecutor().execute(() -> {
            NativeImage decoded = ImageCodec.decodeAndScale(png, ChatImage.MAX_SIDE);
            Minecraft.getInstance().execute(() -> install(image, gen, decoded));
        });
    }

    /** 解码完成，回到渲染线程上传贴图 */
    private static void install(UUID image, int gen, NativeImage decoded) {
        if (gen != generation) {
            // 期间清过场（退出会话、离开世界），这张白解了
            if (decoded != null) decoded.close();
            return;
        }

        Entry entry = ENTRIES.get(image);
        if (entry == null) {
            entry = new Entry();
            ENTRIES.put(image, entry);
        }

        if (decoded == null) {
            entry.status = Status.BROKEN;
            return;
        }
        if (entry.status == Status.READY) {
            // 别的路径已经装好了（比如自己发的图，回声与 seed 撞在一起）
            decoded.close();
            return;
        }

        entry.texture = ImageCodec.upload(decoded, "chat_image_");
        entry.status = Status.READY;
    }

    /**
     * 释放全部贴图。退出会话界面、离开世界时调用。
     *
     * 与相册的 releaseAll 同理：这些贴图对手机的其他界面毫无用处，留着白占显存。
     * 正在解码的不作处理——世代一变，它们完成时会自行丢弃。
     */
    public static void clear() {
        for (Entry entry : ENTRIES.values()) ImageCodec.release(entry.texture);
        ENTRIES.clear();
        lastRequestMs = 0L;
        generation++;
    }
}
