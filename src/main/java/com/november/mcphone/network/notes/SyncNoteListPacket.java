package com.november.mcphone.network.notes;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import com.november.mcphone.notes.NoteList;
import com.november.mcphone.notes.NoteSummary;
import net.minecraft.network.codec.ByteBufCodecs;

import java.util.List;

/**
 * 网络包：服务端 → 客户端，笔记列表（只有摘要，没有全文）。
 *
 * 条数上限直接取存储侧的上限：服务端最多就存那么多条，传输上限比它大
 * 没有意义，比它小则会截断真实数据。两处共用同一个常量，日后改存储
 * 上限不会忘了改这里。
 */
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
