package com.november.mcphone.feature.chat.net;

import net.minecraft.network.FriendlyByteBuf;
import java.util.UUID;

/** 个人删除的请求/确认；所有者来自连接身份，票据约束客户端响应归属。 */
public record DeleteChatMessagePacket(UUID peer, UUID messageId, UUID requestId) {
    public static void encode(DeleteChatMessagePacket msg, FriendlyByteBuf buf) { buf.writeUUID(msg.peer()); buf.writeUUID(msg.messageId()); buf.writeUUID(msg.requestId()); }
    public static DeleteChatMessagePacket decode(FriendlyByteBuf buf) { return new DeleteChatMessagePacket(buf.readUUID(), buf.readUUID(), buf.readUUID()); }
}
