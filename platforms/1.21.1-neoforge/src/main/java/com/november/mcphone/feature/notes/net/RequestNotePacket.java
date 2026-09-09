package com.november.mcphone.feature.notes.net;

import com.november.mcphone.MCphone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 网络包：客户端 → 服务端，请求某一条笔记的全文（列表只带摘要，点进去才拉） */
public record RequestNotePacket(int id) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestNotePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "request_note"));

    public static final StreamCodec<FriendlyByteBuf, RequestNotePacket> STREAM_CODEC =
            StreamCodec.of((buf, msg) -> encode(msg, buf), RequestNotePacket::decode);

    public static void encode(RequestNotePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.id());
    }

    public static RequestNotePacket decode(FriendlyByteBuf buf) {
        return new RequestNotePacket(buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
