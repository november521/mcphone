package com.november.mcphone.feature.notes.net;

import com.november.mcphone.MCphone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 网络包：客户端 → 服务端，请求笔记列表。不带字段：玩家取自连接上下文，不许指定别人 */
public record RequestNoteListPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestNoteListPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "request_note_list"));

    public static final StreamCodec<FriendlyByteBuf, RequestNoteListPacket> STREAM_CODEC =
            StreamCodec.of((buf, msg) -> encode(msg, buf), RequestNoteListPacket::decode);

    /** 没有字段，一个字节都不写。多写一个字节，对面就会把它当成下一个包的开头 */
    public static void encode(RequestNoteListPacket msg, FriendlyByteBuf buf) {
    }

    public static RequestNoteListPacket decode(FriendlyByteBuf buf) {
        return new RequestNoteListPacket();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
