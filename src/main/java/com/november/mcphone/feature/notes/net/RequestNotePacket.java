package com.november.mcphone.feature.notes.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：客户端 → 服务端，请求某一条笔记的全文。
 *
 * 点进某条时才发。列表只带摘要，全文按需拉取——玩家一次只看一条，
 * 把五十条全文都推下去是白费带宽。
 */
public record RequestNotePacket(int id) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestNotePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "request_note"));

    public static final StreamCodec<ByteBuf, RequestNotePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, RequestNotePacket::id,
                    RequestNotePacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
