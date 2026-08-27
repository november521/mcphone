package com.november.mcphone.feature.chat.net;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** C2S：点进会话时请求历史消息；会话列表只带摘要，历史按需拉取。 */
public record RequestMessagesPacket(UUID peer) {

    public static void encode(RequestMessagesPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.peer());
    }

    public static RequestMessagesPacket decode(FriendlyByteBuf buf) {
        return new RequestMessagesPacket(
                buf.readUUID());
    }
}
