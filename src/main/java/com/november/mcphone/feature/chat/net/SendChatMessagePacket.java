package com.november.mcphone.feature.chat.net;

import net.minecraft.network.FriendlyByteBuf;
import com.november.mcphone.feature.chat.ChatMessage;

import java.util.UUID;

/** C2S：发一条消息。发件人与时间戳都不带：让客户端指定发件人等于允许冒充，时间戳一律以服务端为准。 */
public record SendChatMessagePacket(UUID target, String text) {

    public static void encode(SendChatMessagePacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.target());
        buf.writeUtf(msg.text(), ChatMessage.MAX_TEXT_LENGTH);
    }

    public static SendChatMessagePacket decode(FriendlyByteBuf buf) {
        return new SendChatMessagePacket(
                buf.readUUID(),
                buf.readUtf(ChatMessage.MAX_TEXT_LENGTH));
    }
}
