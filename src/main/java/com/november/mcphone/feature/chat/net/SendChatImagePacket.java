package com.november.mcphone.feature.chat.net;

import com.november.mcphone.feature.chat.ChatImage;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * C2S：发一张图片里的一片。
 *
 * 为什么一张图要发好几个包
 *
 * 原版对客户端发上来的自定义包有 32767 字节的硬上限，而一张压过的照片常在 30～80 KB。
 * 于是切成 {@link ChatImage#CHUNK_BYTES} 一片按序发，服务端拼回去（见 ChatImageUploads）。
 *
 * 为什么每一片都带着收件人、宽高与帧信息
 *
 * 服务端要拿它们与本次上传的第一片核对：对不上就整次作废。多带这几十个字节，
 * 换来的是"服务端不必相信后续几片仍属于同一张图"。
 *
 * width/height 是【一帧】的大小。动图的字节是所有帧拼成的一张雪碧图（见 ChatImage），
 * 比一帧大好几倍；而收件人排版时要知道的是一帧多大。frames = 1 就是普通静态图。
 *
 * 图片 id 不在这里：那是服务端存下来之后才有的，让客户端指定等于允许它覆盖别人的图。
 */
public record SendChatImagePacket(UUID target, int width, int height, int frames, int frameMs,
                                  int chunkIndex, int chunkCount, byte[] chunk) {

    public static void encode(SendChatImagePacket value, FriendlyByteBuf buf) {
        buf.writeUUID(value.target());
        buf.writeVarInt(value.width());
        buf.writeVarInt(value.height());
        buf.writeVarInt(value.frames());
        buf.writeVarInt(value.frameMs());
        buf.writeVarInt(value.chunkIndex());
        buf.writeVarInt(value.chunkCount());
        buf.writeByteArray(value.chunk());
    }

    public static SendChatImagePacket decode(FriendlyByteBuf buf) {
        UUID target = buf.readUUID();
        int width = buf.readVarInt();
        int height = buf.readVarInt();
        int frames = buf.readVarInt();
        int frameMs = buf.readVarInt();
        int chunkIndex = buf.readVarInt();
        int chunkCount = buf.readVarInt();
        // 超长的一片在解码阶段就被拒收，轮不到业务层
        byte[] chunk = buf.readByteArray(ChatImage.CHUNK_BYTES);
        return new SendChatImagePacket(target, width, height, frames, frameMs,
                chunkIndex, chunkCount, chunk);
    }
}
