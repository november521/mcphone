package com.november.mcphone.feature.notes.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 网络包：客户端 → 服务端，请求笔记列表。不带字段：玩家取自连接上下文，不许指定别人 */
public record RequestNoteListPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestNoteListPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "request_note_list"));

    public static final StreamCodec<ByteBuf, RequestNoteListPacket> STREAM_CODEC =
            StreamCodec.unit(new RequestNoteListPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
