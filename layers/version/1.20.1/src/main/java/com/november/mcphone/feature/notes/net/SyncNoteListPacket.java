package com.november.mcphone.feature.notes.net;

import com.november.mcphone.core.net.Wire;
import net.minecraft.network.FriendlyByteBuf;
import com.november.mcphone.feature.notes.NoteList;
import com.november.mcphone.feature.notes.NoteSummary;

import java.util.List;

/** 网络包：服务端 → 客户端，笔记列表（只有摘要，没有全文） */
public record SyncNoteListPacket(List<NoteSummary> notes) {

    public static void encode(SyncNoteListPacket msg, FriendlyByteBuf buf) {
        Wire.writeList(buf, msg.notes(), NoteList.MAX_COUNT, NoteSummary::encode);
    }

    public static SyncNoteListPacket decode(FriendlyByteBuf buf) {
        return new SyncNoteListPacket(
                Wire.readList(buf, NoteList.MAX_COUNT, NoteSummary::decode));
    }
}
