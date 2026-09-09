package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** C2S：传送到某个好友面前。故意只带目标 UUID：坐标、维度与全部校验都由服务端现查，客户端报什么都不采信。 */
public record TeleportToFriendPacket(UUID target) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<TeleportToFriendPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "teleport_to_friend"));

    public static final StreamCodec<ByteBuf, TeleportToFriendPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, TeleportToFriendPacket::target,
                    TeleportToFriendPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
