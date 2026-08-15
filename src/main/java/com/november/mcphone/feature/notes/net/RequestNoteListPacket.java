package com.november.mcphone.feature.notes.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：客户端 → 服务端，请求笔记列表。
 *
 * 不带任何字段：要哪个玩家的笔记，服务端从连接上下文就知道。
 * 让客户端指定玩家等于允许翻别人的笔记本。
 */
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
