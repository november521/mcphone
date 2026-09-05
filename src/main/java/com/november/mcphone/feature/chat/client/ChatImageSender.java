package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.client.ImageCodec;
import com.november.mcphone.feature.chat.ChatImage;
import com.november.mcphone.feature.chat.ChatMessage;
import com.november.mcphone.feature.chat.ImageBody;
import com.november.mcphone.feature.chat.net.SendChatImagePacket;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 把相册里的一张照片发给好友：压 → 切片 → 发。
 *
 * 为什么要在客户端压
 *
 * 截图是全屏分辨率的，一张 1920×1080 的 PNG 常有两三 MB。原样发上去，一是根本发不动
 * （客户端发给服务端的包上限 32767 字节），二是服主要替所有人存那么大的文件。而手机
 * 气泡里那张最宽只有 80 个 GUI 像素，但图是点得开的，放大后要铺满整块屏幕，见 {@link ChatImage#MAX_SIDE}。
 *
 * 压完还是太大怎么办
 *
 * 降一档尺寸再压一次（见 {@link #FALLBACK_SIDES}）。PNG 是无损的，一张噪点多的截图
 * （雨天、树叶、粒子）压出来能比一张干净的大好几倍，光按尺寸算压不出准头。
 *
 * 一次只发一张
 *
 * 界面上也只能选一张，这道闸是防手快：压缩是异步的，连点两下会有两次上传交错着发上去，
 * 而服务端按"片号必须连续"收（见 ChatImageUploads），交错的结果是两张都发不成。
 *
 * 线程：读盘与压缩在后台，发包回到渲染线程——网络那一端不该被后台线程碰。
 */
public final class ChatImageSender {

    private ChatImageSender() {}

    /** 压不进上限时依次降到这几档长边。降一档面积就少三成多，噪点最狠的画面也能落进上限 */
    private static final int[] FALLBACK_SIDES = {320, 256, 192};

    /** 一次上传最多允许拖这么久，超时就当它没发出去，放开下一次 */
    private static final long SEND_TIMEOUT_MS = 15_000L;

    /**
     * 发完一张之后多久才能再发。与服务端 RequestThrottle 的 CHAT_IMAGE 一致。
     *
     * 客户端这边也拦一道，是为了别让正常操作撞上服务端那道闸：撞上了服务端只会回一句
     * "缓一下"，而客户端还在等一个永远不会来的回声，那个键要一直灰到超时。
     */
    private static final long COOLDOWN_MS = 2_000L;

    private static long sendingSince;

    /** 冷却到期的时刻 */
    private static long readyAt;

    /** 刚发上去的那张图的字节，等回声带着 id 回来时塞进缓存，见 {@link #onNewMessage} */
    private static byte[] pendingPng;

    /** 这会儿不能发（正在发，或者刚发完还在冷却）。界面据此把图片键画成灰的 */
    public static boolean isBusy() {
        long now = System.currentTimeMillis();

        if (sendingSince != 0L) {
            if (now - sendingSince <= SEND_TIMEOUT_MS) return true;
            // 服务端拒收时不会有回声（拒收的理由它已经单独说过了），不能让界面永远卡在"发送中"
            finish();
        }
        return now < readyAt;
    }

    /**
     * 发一张。立刻返回，压缩与发包都在后面。
     *
     * @param peer  收件人
     * @param photo 相册里那张照片的路径
     */
    public static void send(UUID peer, Path photo) {
        if (peer == null || photo == null || isBusy()) return;

        sendingSince = System.currentTimeMillis();
        pendingPng = null;

        Util.backgroundExecutor().execute(() -> {
            ImageCodec.Encoded encoded = encodeWithinLimit(photo);
            Minecraft.getInstance().execute(() -> upload(peer, encoded));
        });
    }

    /** 压到上限之内；每一档都压不下来返回 null */
    private static ImageCodec.Encoded encodeWithinLimit(Path photo) {
        ImageCodec.Encoded encoded = ImageCodec.encodePng(photo, ChatImage.MAX_SIDE);
        if (encoded != null && encoded.png().length <= ChatImage.MAX_BYTES) return encoded;

        for (int side : FALLBACK_SIDES) {
            encoded = ImageCodec.encodePng(photo, side);
            if (encoded != null && encoded.png().length <= ChatImage.MAX_BYTES) return encoded;
        }
        return null;
    }

    /** 切片发上去。在渲染线程 */
    private static void upload(UUID peer, ImageCodec.Encoded encoded) {
        if (encoded == null) {
            tell("mcphone.chat.image_encode_failed");
            finish();
            return;
        }
        if (Minecraft.getInstance().getConnection() == null) {
            finish();   // 压缩那会儿工夫里断线了
            return;
        }

        byte[] png = encoded.png();
        int chunkCount = (png.length + ChatImage.CHUNK_BYTES - 1) / ChatImage.CHUNK_BYTES;

        for (int index = 0; index < chunkCount; index++) {
            int from = index * ChatImage.CHUNK_BYTES;
            int to = Math.min(png.length, from + ChatImage.CHUNK_BYTES);

            byte[] chunk = new byte[to - from];
            System.arraycopy(png, from, chunk, 0, chunk.length);

            PacketDistributor.sendToServer(new SendChatImagePacket(
                    peer, encoded.width(), encoded.height(), index, chunkCount, chunk));
        }

        // 等回声。同一条连接上包是有序的，服务端拼齐后会把消息发回来
        pendingPng = png;
    }

    /**
     * 收到任何一条新消息时都过一道：如果是自己刚发的那张图的回声，把像素直接塞进缓存。
     *
     * 回声里只有图片 id（见 ImageBody），不这么做的话，发件人会看着自己刚发出去的图
     * 转一圈"加载中"，再从服务器把自己上传的东西下回来。
     *
     * 由 {@link ChatNotifier#onMessage} 转调——那里本来就是"每条新消息都会经过"的地方。
     */
    public static void onNewMessage(ChatMessage message) {
        if (pendingPng == null) return;
        if (!(message.body() instanceof ImageBody image)) return;

        var player = Minecraft.getInstance().player;
        if (player == null || !message.sender().equals(player.getUUID())) return;

        ChatImageCache.seed(image.image(), pendingPng);
        finish();
    }

    /** 退出世界时清掉，免得下一个服务器里冒出一次莫名其妙的"发送中"，也不必带着冷却过去 */
    public static void clear() {
        sendingSince = 0L;
        pendingPng = null;
        readyAt = 0L;
    }

    /** 一次上传就此结束（成了、超时了、或者压根没发出去），并开始冷却 */
    private static void finish() {
        sendingSince = 0L;
        pendingPng = null;
        readyAt = System.currentTimeMillis() + COOLDOWN_MS;
    }

    private static void tell(String translationKey) {
        var player = Minecraft.getInstance().player;
        // 与服务端拒收时同一个位置：动作栏。玩家的眼睛正看着手机屏幕，聊天框那一行他看不见
        if (player != null) player.displayClientMessage(Component.translatable(translationKey), true);
    }
}
