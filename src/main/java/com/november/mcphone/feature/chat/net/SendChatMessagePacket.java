package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.chat.TextBody;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** C2S：发一条消息。发件人与时间戳都不带：让客户端指定发件人等于允许冒充，时间戳一律以服务端为准。 */
public record SendChatMessagePacket(UUID target, String text) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SendChatMessagePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "send_chat_message"));

    public static final StreamCodec<ByteBuf, SendChatMessagePacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, SendChatMessagePacket::target,
                    ByteBufCodecs.stringUtf8(TextBody.MAX_LENGTH), SendChatMessagePacket::text,
                    SendChatMessagePacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
