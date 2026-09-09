package com.november.mcphone.feature.notes.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.net.Wire;
import com.november.mcphone.feature.notes.NoteList;
import com.november.mcphone.feature.notes.NoteSummary;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** 网络包：服务端 → 客户端，笔记列表（只有摘要，没有全文） */
public record SyncNoteListPacket(List<NoteSummary> notes) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncNoteListPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "sync_note_list"));

    public static final StreamCodec<FriendlyByteBuf, SyncNoteListPacket> STREAM_CODEC =
            StreamCodec.of((buf, msg) -> encode(msg, buf), SyncNoteListPacket::decode);

    public static void encode(SyncNoteListPacket msg, FriendlyByteBuf buf) {
        Wire.writeList(buf, msg.notes(), NoteList.MAX_COUNT, NoteSummary::encode);
    }

    public static SyncNoteListPacket decode(FriendlyByteBuf buf) {
        return new SyncNoteListPacket(
                Wire.readList(buf, NoteList.MAX_COUNT, NoteSummary::decode));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
