package com.november.mcphone.network.chat;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 网络包：客户端 → 服务端，向某人发出好友申请。
 *
 * 只带 UUID，不带名字：名字由服务端自己解析。让客户端指定名字的话，
 * 伪造客户端就能往别人的存档里写任意字符串。
 *
 * 申请不等于成为好友——要等对方同意。单向加好友是此前的设计错误：
 * 玩家1 加了玩家2，玩家2 那边什么都不会发生。
 */
public record FriendRequestPacket(UUID target) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FriendRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "friend_request"));

    public static final StreamCodec<ByteBuf, FriendRequestPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, FriendRequestPacket::target,
                    FriendRequestPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
