package com.november.mcphone.feature.notes.net;

import net.minecraft.network.FriendlyByteBuf;

/** 网络包：客户端 → 服务端，请求某一条笔记的全文（列表只带摘要，点进去才拉） */
public record RequestNotePacket(int id) {

    public static void encode(RequestNotePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.id());
    }

    public static RequestNotePacket decode(FriendlyByteBuf buf) {
        return new RequestNotePacket(
                buf.readVarInt());
    }
}
