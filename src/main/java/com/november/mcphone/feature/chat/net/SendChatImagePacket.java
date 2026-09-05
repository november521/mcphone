package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.chat.ChatImage;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * C2S：发一张图片里的一片。
 *
 * 为什么一张图要发好几个包
 *
 * 原版对客户端发上来的自定义包有 32767 字节的硬上限，而一张压过的照片常在 30～80 KB。
 * 于是切成 {@link ChatImage#CHUNK_BYTES} 一片按序发，服务端拼回去（见 ChatImageUploads）。
 *
 * 为什么每一片都带着收件人与宽高
 *
 * 服务端要拿它们与本次上传的第一片核对：对不上就整次作废。多带这几十个字节，
 * 换来的是"服务端不必相信后续几片仍属于同一张图"。
 *
 * 图片 id 不在这里：那是服务端存下来之后才有的，让客户端指定等于允许它覆盖别人的图。
 */
public record SendChatImagePacket(UUID target, int width, int height,
                                  int chunkIndex, int chunkCount, byte[] chunk)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SendChatImagePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "send_chat_image"));

    /** 手写而不用 composite：composite 最多 6 个字段，本记录正好 6 个，用它以后加字段就得推倒重写 */
    public static final StreamCodec<ByteBuf, SendChatImagePacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SendChatImagePacket decode(ByteBuf buf) {
                    UUID target = UUIDUtil.STREAM_CODEC.decode(buf);
                    int width = ByteBufCodecs.VAR_INT.decode(buf);
                    int height = ByteBufCodecs.VAR_INT.decode(buf);
                    int chunkIndex = ByteBufCodecs.VAR_INT.decode(buf);
                    int chunkCount = ByteBufCodecs.VAR_INT.decode(buf);
                    // 超长的一片在解码阶段就被拒收，轮不到业务层
                    byte[] chunk = ByteBufCodecs.byteArray(ChatImage.CHUNK_BYTES).decode(buf);
                    return new SendChatImagePacket(target, width, height,
                            chunkIndex, chunkCount, chunk);
                }

                @Override
                public void encode(ByteBuf buf, SendChatImagePacket value) {
                    UUIDUtil.STREAM_CODEC.encode(buf, value.target());
                    ByteBufCodecs.VAR_INT.encode(buf, value.width());
                    ByteBufCodecs.VAR_INT.encode(buf, value.height());
                    ByteBufCodecs.VAR_INT.encode(buf, value.chunkIndex());
                    ByteBufCodecs.VAR_INT.encode(buf, value.chunkCount());
                    ByteBufCodecs.byteArray(ChatImage.CHUNK_BYTES).encode(buf, value.chunk());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
