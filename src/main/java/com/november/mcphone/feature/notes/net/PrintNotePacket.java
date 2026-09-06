package com.november.mcphone.feature.notes.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 网络包：客户端 → 服务端，把某条笔记印成书。只带 id：采信客户端送来的正文会被伪造 */
public record PrintNotePacket(int id) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PrintNotePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "print_note"));

    public static final StreamCodec<ByteBuf, PrintNotePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PrintNotePacket::id,
                    PrintNotePacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
