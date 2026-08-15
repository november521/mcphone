package com.november.mcphone.feature.notes.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.notes.Note;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：客户端 → 服务端，保存一条笔记。
 *
 * id 为 NoteService.NEW_NOTE_ID（0）表示新建，真正的 id 由服务端分配——
 * 让客户端自己挑的话，两个界面同时新建就会撞号。
 *
 * 时间戳不带：客户端的钟不可信，早一点晚一点会打乱列表排序，极端情况下
 * 能把自己的笔记永远顶在最前。一律以服务端落笔的时刻为准。
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
