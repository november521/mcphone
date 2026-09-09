package com.november.mcphone.feature.chat.net;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** C2S：发出好友申请，要等对方同意才成为好友。只带 UUID 不带名字：让客户端报名字等于允许往别人的存档写任意字符串。 */
public record FriendRequestPacket(UUID target) {

    public static void encode(FriendRequestPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.target());
    }

    public static FriendRequestPacket decode(FriendlyByteBuf buf) {
        return new FriendRequestPacket(
                buf.readUUID());
    }
}
