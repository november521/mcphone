package com.november.mcphone.feature.notes.net;

import net.minecraft.network.FriendlyByteBuf;

/** 网络包：客户端 → 服务端，把某条笔记印成书。只带 id：采信客户端送来的正文会被伪造 */
public record PrintNotePacket(int id) {

    public static void encode(PrintNotePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.id());
    }

    public static PrintNotePacket decode(FriendlyByteBuf buf) {
        return new PrintNotePacket(
                buf.readVarInt());
    }
}
