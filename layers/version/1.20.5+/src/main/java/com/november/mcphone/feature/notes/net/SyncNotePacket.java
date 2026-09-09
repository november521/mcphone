package com.november.mcphone.feature.notes.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.notes.Note;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 网络包：服务端 → 客户端，某条笔记的全文；已被删时 id 照带但正文为空，由界面退回列表 */
public record SyncNotePacket(Note note) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncNotePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "sync_note"));

    public static final StreamCodec<FriendlyByteBuf, SyncNotePacket> STREAM_CODEC =
            StreamCodec.of((buf, msg) -> encode(msg, buf), SyncNotePacket::decode);

    public static void encode(SyncNotePacket msg, FriendlyByteBuf buf) {
        Note.encode(msg.note(), buf);
    }

    public static SyncNotePacket decode(FriendlyByteBuf buf) {
        return new SyncNotePacket(Note.decode(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
