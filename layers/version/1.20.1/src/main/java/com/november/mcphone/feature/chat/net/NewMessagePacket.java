package com.november.mcphone.feature.chat.net;

import net.minecraft.network.FriendlyByteBuf;
import com.november.mcphone.feature.chat.ChatMessage;

import java.util.UUID;

/**
 * S2C：来了一条新消息。只推这一条，不重发整个列表；收发双方都收，发件人靠回声显示自己那条。
 * peer 站在收包方的角度是会话对端：收件人收到时是发件人，发件人收到回声时是收件人。
 */
public record NewMessagePacket(UUID peer, ChatMessage message) {

    public static void encode(NewMessagePacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.peer());
        ChatMessage.encode(msg.message(), buf);
    }

    public static NewMessagePacket decode(FriendlyByteBuf buf) {
        return new NewMessagePacket(
                buf.readUUID(),
                ChatMessage.decode(buf));
    }
}
