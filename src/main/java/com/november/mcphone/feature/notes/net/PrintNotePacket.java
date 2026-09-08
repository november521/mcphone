package com.november.mcphone.feature.notes.net;

import com.november.mcphone.MCphone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 网络包：客户端 → 服务端，把某条笔记印成书。只带 id：采信客户端送来的正文会被伪造 */
public record PrintNotePacket(int id) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PrintNotePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "print_note"));

    public static final StreamCodec<FriendlyByteBuf, PrintNotePacket> STREAM_CODEC =
            StreamCodec.of((buf, msg) -> encode(msg, buf), PrintNotePacket::decode);

    public static void encode(PrintNotePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.id());
    }

    public static PrintNotePacket decode(FriendlyByteBuf buf) {
        return new PrintNotePacket(buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
