package com.november.mcphone.network.notes;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import com.november.mcphone.notes.Note;

/**
 * 网络包：服务端 → 客户端，某一条笔记的全文。
 *
 * 笔记可能已经被删掉（比如玩家在另一个界面删了它），这时 id 仍然带回来
 * 但正文为空，由界面自行退回列表——单独设一个"没找到"的包不值得。
 */
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
