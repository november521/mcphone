package com.november.mcphone.feature.chat.net;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** C2S：解除好友，双向。聊天记录不删——那是双方共有的，重新加回好友历史还在。 */
public record RemoveFriendPacket(UUID target) {

    public static void encode(RemoveFriendPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.target());
    }

    public static RemoveFriendPacket decode(FriendlyByteBuf buf) {
        return new RemoveFriendPacket(
                buf.readUUID());
    }
}
