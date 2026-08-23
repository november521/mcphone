package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 网络包：客户端 → 服务端，传送到某个好友面前。
 *
 * 包体里只有目标的 UUID，没有坐标、没有维度——这是刻意的。落点由服务端
 * 现查对方的位置算出来，客户端报什么都不采信：允许它报坐标的话，
 * 一个改过的客户端就能把自己传到地图上任何一处，好友只是个幌子。
 *
 * "是不是好友""对方在不在线""手机在不在身上"同理，全在服务端查，
 * 见 {@link com.november.mcphone.feature.chat.TeleportService}。
 */
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
