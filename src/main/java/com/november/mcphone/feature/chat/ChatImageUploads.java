package com.november.mcphone.feature.chat;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端这边正在收的图：把客户端切开的几片拼回一张 PNG。
 *
 * 为什么要拼
 *
 * 原版对客户端发上来的自定义包有 32767 字节的硬上限，一张图装不下，只能切片。
 * 切片就意味着服务端要在两个包之间记住"这个人正在发一张图，已经收到几片了"，
 * 这个类就是那份记忆。
 *
 * 每人同时只允许一次上传
 *
 * 界面上一次也只能选一张，因此这不是限制，而是一道闸：不这么定的话，一个改过的
 * 客户端可以同时开一千个上传，每个都占着几十 KB 的服务端内存，而它一片都不必发完。
 * 新的一次上传（片号 0）直接顶掉旧的，正常玩家换一张图重发时也是这个路径。
 *
 * 收不齐的怎么办
 *
 * 什么都不做，等下一次上传顶掉它，或者玩家下线时清掉。另外每一片都会先检查
 * {@link ChatImage#UPLOAD_TIMEOUT_MS}：网断在半路的那一次不能永远占着内存。
 *
 * 线程：包处理都在 enqueueWork 里（服务端主线程）。用并发容器的理由与
 * {@link com.november.mcphone.core.net.RequestThrottle} 相同——不必去论证"将来也只有主线程碰它"。
 */
public final class ChatImageUploads {

    private ChatImageUploads() {}

    /** 拼齐了的一张图 */
    public record Assembled(UUID target, int width, int height, byte[] png) {}

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private static final class Session {
        final UUID target;
        final int width;
        final int height;
        final int chunkCount;
        final byte[] buffer;
        final long startedAt;

        int nextChunk;
        int size;

        Session(UUID target, int width, int height, int chunkCount, long now) {
            this.target = target;
            this.width = width;
            this.height = height;
            this.chunkCount = chunkCount;
            this.buffer = new byte[Math.min(ChatImage.MAX_BYTES, chunkCount * ChatImage.CHUNK_BYTES)];
            this.startedAt = now;
        }
    }

    /**
     * 收下一片。返回非 null 表示这一张已经收齐了。
     *
     * 任何对不上的地方都直接丢掉整次上传并返回 null：正常客户端按顺序发完一张才发下一张，
     * 对不上的只可能是伪造客户端，或者中途断过——两种情况都没有"尽力补救"的价值，
     * 而每多一条补救路径就多一处能被绕开的地方。
     */
    public static Assembled accept(ServerPlayer player, UUID target, int width, int height,
                                   int chunkIndex, int chunkCount, byte[] chunk) {

        UUID playerId = player.getUUID();
        long now = System.currentTimeMillis();

        if (chunkIndex == 0) {
            if (chunkCount < 1 || chunkCount > ChatImage.MAX_CHUNKS) {
                SESSIONS.remove(playerId);
                return null;
            }
            SESSIONS.put(playerId, new Session(target, width, height, chunkCount, now));
        }

        Session session = SESSIONS.get(playerId);
        if (session == null) return null;

        boolean matches = session.nextChunk == chunkIndex
                && session.chunkCount == chunkCount
                && session.target.equals(target)
                && session.width == width
                && session.height == height
                && now - session.startedAt <= ChatImage.UPLOAD_TIMEOUT_MS
                && chunk.length <= ChatImage.CHUNK_BYTES
                && session.size + chunk.length <= session.buffer.length;

        if (!matches) {
            SESSIONS.remove(playerId);
            return null;
        }

        System.arraycopy(chunk, 0, session.buffer, session.size, chunk.length);
        session.size += chunk.length;
        session.nextChunk++;

        if (session.nextChunk < session.chunkCount) return null;

        SESSIONS.remove(playerId);

        // buffer 是按上限开的，末片多半没填满，截到真实长度再交出去
        byte[] png = new byte[session.size];
        System.arraycopy(session.buffer, 0, png, 0, session.size);
        return new Assembled(session.target, session.width, session.height, png);
    }

    /**
     * 玩家下线时把他没发完的那一次丢掉。
     *
     * 与 RequestThrottle 那张表同理：不清的话，每个发了一半就跑掉的人都会留下几十 KB，
     * 而这份内存要等到服务器重启才还回来。挂载见 MCphone 的构造函数。
     */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SESSIONS.remove(event.getEntity().getUUID());
    }
}
