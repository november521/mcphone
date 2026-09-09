package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** C2S：同意（accept=true）或拒绝一条好友申请，两者校验相同所以合成一个包；两种情况都会消掉申请。 */
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
