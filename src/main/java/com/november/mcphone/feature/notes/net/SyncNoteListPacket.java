package com.november.mcphone.feature.notes.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.notes.NoteList;
import com.november.mcphone.feature.notes.NoteSummary;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** 网络包：服务端 → 客户端，笔记列表（只有摘要，没有全文） */
public record SyncNoteListPacket(List<NoteSummary> notes) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncNoteListPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "sync_note_list"));

    public static final StreamCodec<ByteBuf, SyncNoteListPacket> STREAM_CODEC =
            StreamCodec.composite(
                    NoteSummary.STREAM_CODEC.apply(ByteBufCodecs.list(NoteList.MAX_COUNT)),
                    SyncNoteListPacket::notes,
                    SyncNoteListPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
