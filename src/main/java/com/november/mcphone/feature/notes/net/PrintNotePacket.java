package com.november.mcphone.feature.notes.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：客户端 → 服务端，把某条笔记印成一本书。
 *
 * 只带 id：正文以服务端存的那份为准。让客户端把正文一并送上来的话，
 * 改个客户端就能印出任意内容的书，笔记本身反倒成了摆设。
 */
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
