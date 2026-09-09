package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** C2S：解除好友，双向。聊天记录不删——那是双方共有的，重新加回好友历史还在。 */
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
