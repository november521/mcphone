package com.november.mcphone.feature.chat.net;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * C2S：会话开着时补一次已读。不复用拉历史那个包（那要把整段历史重传一遍）；
 * 不带时间戳，已读时刻由服务端盖章，否则报个未来时间就能让红点永远不出现。
 */
public record MarkReadPacket(UUID peer) {

    public static void encode(MarkReadPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.peer());
    }

    public static MarkReadPacket decode(FriendlyByteBuf buf) {
        return new MarkReadPacket(
                buf.readUUID());
    }
}
