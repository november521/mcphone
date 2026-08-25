package com.november.mcphone.feature.notes.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.notes.Note;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：客户端 → 服务端，保存一条笔记。id 为 NoteService.NEW_NOTE_ID 表示新建，
 * 真正的 id 由服务端分配；不带时间戳，客户端的钟不可信。
 */
public record SaveNotePacket(int id, String body) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SaveNotePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "save_note"));

    public static final StreamCodec<ByteBuf, SaveNotePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, SaveNotePacket::id,
                    ByteBufCodecs.stringUtf8(Note.MAX_BODY_LENGTH), SaveNotePacket::body,
                    SaveNotePacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
