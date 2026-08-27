package com.november.mcphone.feature.notes.net;

import net.minecraft.network.FriendlyByteBuf;
import com.november.mcphone.feature.notes.Note;

/** 网络包：服务端 → 客户端，某条笔记的全文；已被删时 id 照带但正文为空，由界面退回列表 */
public record SyncNotePacket(Note note) {

    public static void encode(SyncNotePacket msg, FriendlyByteBuf buf) {
        Note.encode(msg.note(), buf);
    }

    public static SyncNotePacket decode(FriendlyByteBuf buf) {
        return new SyncNotePacket(
                Note.decode(buf));
    }
}
