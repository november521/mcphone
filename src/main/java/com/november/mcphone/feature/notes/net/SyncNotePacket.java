package com.november.mcphone.feature.notes.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.notes.Note;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 网络包：服务端 → 客户端，某条笔记的全文；已被删时 id 照带但正文为空，由界面退回列表 */
public record SyncNotePacket(Note note) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncNotePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "sync_note"));

    public static final StreamCodec<ByteBuf, SyncNotePacket> STREAM_CODEC =
            StreamCodec.composite(
                    Note.STREAM_CODEC, SyncNotePacket::note,
                    SyncNotePacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
