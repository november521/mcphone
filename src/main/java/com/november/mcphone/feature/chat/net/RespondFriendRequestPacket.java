package com.november.mcphone.feature.chat.net;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** C2S：同意（accept=true）或拒绝一条好友申请，两者校验相同所以合成一个包；两种情况都会消掉申请。 */
public record RespondFriendRequestPacket(UUID requester, boolean accept) {

    public static void encode(RespondFriendRequestPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.requester());
        buf.writeBoolean(msg.accept());
    }

    public static RespondFriendRequestPacket decode(FriendlyByteBuf buf) {
        return new RespondFriendRequestPacket(
                buf.readUUID(),
                buf.readBoolean());
    }
}
