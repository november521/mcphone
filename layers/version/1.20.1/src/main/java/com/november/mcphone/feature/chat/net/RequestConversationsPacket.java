package com.november.mcphone.feature.chat.net;

import net.minecraft.network.FriendlyByteBuf;

/** C2S：打开聊天 App 时请求会话列表。故意无字段：玩家取自连接上下文，带玩家 ID 等于给伪造客户端开窥探别人会话的后门。 */
public record RequestConversationsPacket() {

    public static void encode(RequestConversationsPacket msg, FriendlyByteBuf buf) {
    }

    public static RequestConversationsPacket decode(FriendlyByteBuf buf) {
        return new RequestConversationsPacket();
    }
}
