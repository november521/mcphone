package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.chat.ChatMessage;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * S2C：来了一条新消息。只推这一条，不重发整个列表；收发双方都收，发件人靠回声显示自己那条。
 * peer 站在收包方的角度是会话对端：收件人收到时是发件人，发件人收到回声时是收件人。
 */
public record NewMessagePacket(UUID peer, ChatMessage message) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<NewMessagePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "new_chat_message"));

    public static final StreamCodec<ByteBuf, NewMessagePacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, NewMessagePacket::peer,
                    ChatMessage.STREAM_CODEC, NewMessagePacket::message,
                    NewMessagePacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
