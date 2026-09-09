package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** C2S：发出好友申请，要等对方同意才成为好友。只带 UUID 不带名字：让客户端报名字等于允许往别人的存档写任意字符串。 */
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
