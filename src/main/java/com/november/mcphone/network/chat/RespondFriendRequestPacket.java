package com.november.mcphone.network.chat;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 网络包：客户端 → 服务端，处理一条收到的好友申请。
 *
 * 同意与拒绝合成一个包而不是两个：两者的校验完全相同（申请必须真的存在、
 * 且确实是发给我的），分成两个包等于把同一套校验抄两遍，改一处漏一处。
 *
 * @param requester 申请人
 * @param accept    true 同意，false 拒绝。两种情况都会把这条申请消掉，
 *                  区别只在于要不要建立好友关系
 */
public record RespondFriendRequestPacket(UUID requester, boolean accept)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RespondFriendRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "respond_friend_request"));

    public static final StreamCodec<ByteBuf, RespondFriendRequestPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, RespondFriendRequestPacket::requester,
                    ByteBufCodecs.BOOL, RespondFriendRequestPacket::accept,
                    RespondFriendRequestPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
