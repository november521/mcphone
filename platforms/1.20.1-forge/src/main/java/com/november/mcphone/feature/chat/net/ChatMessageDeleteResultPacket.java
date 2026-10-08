package com.november.mcphone.feature.chat.net;

import com.november.mcphone.feature.chat.ChatDeletionResult;
import net.minecraft.network.FriendlyByteBuf;
import java.util.UUID;

/** 个人删除的请求/确认；所有者来自连接身份，票据约束客户端响应归属。 */
public record ChatMessageDeleteResultPacket(UUID peer, UUID messageId, UUID requestId, ChatDeletionResult result) {
    public static void encode(ChatMessageDeleteResultPacket msg, FriendlyByteBuf buf) { buf.writeUUID(msg.peer()); buf.writeUUID(msg.messageId()); buf.writeUUID(msg.requestId()); buf.writeVarInt(msg.result().wireId()); }
    public static ChatMessageDeleteResultPacket decode(FriendlyByteBuf buf) { return new ChatMessageDeleteResultPacket(buf.readUUID(), buf.readUUID(), buf.readUUID(), decodeResult(buf.readVarInt())); }
    public static ChatDeletionResult decodeResult(int ordinal) {
        return ChatDeletionResult.fromWire(ordinal);
    }
}
