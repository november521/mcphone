package com.november.mcphone.feature.notes.net;

import net.minecraft.network.FriendlyByteBuf;

/** 网络包：客户端 → 服务端，删掉一条笔记 */
public record DeleteNotePacket(int id) {

    public static void encode(DeleteNotePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.id());
    }

    public static DeleteNotePacket decode(FriendlyByteBuf buf) {
        return new DeleteNotePacket(
                buf.readVarInt());
    }
}
