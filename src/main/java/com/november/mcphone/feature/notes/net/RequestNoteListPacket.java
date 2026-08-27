package com.november.mcphone.feature.notes.net;

import net.minecraft.network.FriendlyByteBuf;

/** 网络包：客户端 → 服务端，请求笔记列表。不带字段：玩家取自连接上下文，不许指定别人 */
public record RequestNoteListPacket() {

    public static void encode(RequestNoteListPacket msg, FriendlyByteBuf buf) {
    }

    public static RequestNoteListPacket decode(FriendlyByteBuf buf) {
        return new RequestNoteListPacket();
    }
}
