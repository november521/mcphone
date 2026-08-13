package com.november.mcphone.network.chat;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 网络包：客户端 → 服务端，请求某个会话的历史消息。
 *
 * 点进某个会话时才发。会话列表只带摘要，历史消息按需拉取——
 * 玩家往往只看一两个会话，把全部历史都推下去是白费带宽。
 */
public record RequestMessagesPacket(UUID peer) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestMessagesPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "request_messages"));

    public static final StreamCodec<ByteBuf, RequestMessagesPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, RequestMessagesPacket::peer,
                    RequestMessagesPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
