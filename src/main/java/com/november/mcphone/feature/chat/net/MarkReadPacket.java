package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * C2S：会话开着时补一次已读。不复用拉历史那个包（那要把整段历史重传一遍）；
 * 不带时间戳，已读时刻由服务端盖章，否则报个未来时间就能让红点永远不出现。
 */
public record MarkReadPacket(UUID peer) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MarkReadPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "mark_read"));

    public static final StreamCodec<ByteBuf, MarkReadPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, MarkReadPacket::peer,
                    MarkReadPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
