package com.november.mcphone.feature.chat.net;

import com.november.mcphone.feature.chat.TextBody;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** C2S：发一条消息。发件人与时间戳都不带：让客户端指定发件人等于允许冒充，时间戳一律以服务端为准。 */
public record SendChatMessagePacket(UUID target, String text, java.util.Optional<UUID> reply, java.util.Optional<UUID> ticket) {
    public SendChatMessagePacket(UUID target, String text) { this(target,text,java.util.Optional.empty()); }
    public SendChatMessagePacket(UUID target, String text, java.util.Optional<UUID> reply) {
        this(target,text,reply,reply.map(ignored -> UUID.randomUUID()));
    }
    public SendChatMessagePacket {
        java.util.Objects.requireNonNull(target); java.util.Objects.requireNonNull(text);
        java.util.Objects.requireNonNull(reply); java.util.Objects.requireNonNull(ticket);
        if (reply.isPresent() != ticket.isPresent()) throw new IllegalArgumentException("引用和提交票据必须同时存在");
    }

    public static void encode(SendChatMessagePacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.target());
        buf.writeUtf(msg.text(), TextBody.MAX_LENGTH);
        ChatReplyWire.writeId(buf,msg.reply());
        ChatReplyWire.writeId(buf,msg.ticket());
    }

    public static SendChatMessagePacket decode(FriendlyByteBuf buf) {
        return new SendChatMessagePacket(
                buf.readUUID(),
                buf.readUtf(TextBody.MAX_LENGTH), ChatReplyWire.readId(buf), ChatReplyWire.readId(buf));
    }
}
