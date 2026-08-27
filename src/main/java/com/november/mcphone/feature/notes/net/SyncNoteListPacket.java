package com.november.mcphone.feature.notes.net;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import com.november.mcphone.feature.notes.NoteList;
import com.november.mcphone.feature.notes.NoteSummary;

import java.util.List;

/** 网络包：服务端 → 客户端，笔记列表（只有摘要，没有全文） */
public record SyncNoteListPacket(List<NoteSummary> notes) {

    public static void encode(SyncNoteListPacket msg, FriendlyByteBuf buf) {
        buf.writeCollection(msg.notes(), (b, v) -> NoteSummary.encode(v, buf));
    }

    public static SyncNoteListPacket decode(FriendlyByteBuf buf) {
        return new SyncNoteListPacket(
                buf.readCollection(n -> {
            if (n > NoteList.MAX_COUNT) throw new DecoderException("列表超过上限 NoteList.MAX_COUNT: " + n);
            return new java.util.ArrayList<>(n);
        }, b -> NoteSummary.decode(buf)));
    }
}
