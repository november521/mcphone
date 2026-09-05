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
 * S2C：一张图的像素。不分片——服务端发给客户端那条路的上限是 1 MB，一张图只有几十 KB。
 *
 * data 是空数组表示【这张图没了】：被上限挤掉了像素、或者服主手动清过图片仓。
 * 空数组而不是干脆不回，是因为客户端必须能分辨"还没到"与"不会来了"：
 * 前者要接着等，后者要把气泡改成「图片已过期」并且不再问第二次。
 */
public record ChatImageDataPacket(UUID image, byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ChatImageDataPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "chat_image_data"));

    public static final StreamCodec<ByteBuf, ChatImageDataPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ChatImageDataPacket::image,
                    ByteBufCodecs.byteArray(ChatImage.MAX_BYTES), ChatImageDataPacket::data,
                    ChatImageDataPacket::new
            );

    /** 没有这张图 */
    public static ChatImageDataPacket gone(UUID image) {
        return new ChatImageDataPacket(image, new byte[0]);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
