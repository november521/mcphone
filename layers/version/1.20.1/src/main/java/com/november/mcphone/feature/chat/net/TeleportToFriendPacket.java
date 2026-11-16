package com.november.mcphone.feature.chat.net;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** C2S：传送到某个好友面前。故意只带目标 UUID：坐标、维度与全部校验都由服务端现查，客户端报什么都不采信。 */
public record TeleportToFriendPacket(UUID target) {

    public static void encode(TeleportToFriendPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.target());
    }

    public static TeleportToFriendPacket decode(FriendlyByteBuf buf) {
        return new TeleportToFriendPacket(
                buf.readUUID());
    }
}
