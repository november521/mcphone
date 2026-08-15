package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 网络包：客户端 → 服务端，解除与某人的好友关系。
 *
 * 解除是双向的：好友关系是共有的一条记录，没有"我删了你但你还留着我"
 * 这种半吊子状态。
 *
 * 聊天记录不删——那是双方共有的，单方面抹掉等于替对方做决定。
 * 日后重新加回好友，历史消息还在。
 */
public record RemoveFriendPacket(UUID target) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RemoveFriendPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "remove_friend"));

    public static final StreamCodec<ByteBuf, RemoveFriendPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, RemoveFriendPacket::target,
                    RemoveFriendPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
