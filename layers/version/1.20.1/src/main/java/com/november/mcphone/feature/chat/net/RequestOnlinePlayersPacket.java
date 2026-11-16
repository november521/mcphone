package com.november.mcphone.feature.chat.net;

import net.minecraft.network.FriendlyByteBuf;

/** C2S：打开"加联系人"界面时请求在线玩家列表，无字段；本人由服务端从连接上下文取。 */
public record RequestOnlinePlayersPacket() {

    public static void encode(RequestOnlinePlayersPacket msg, FriendlyByteBuf buf) {
    }

    public static RequestOnlinePlayersPacket decode(FriendlyByteBuf buf) {
        return new RequestOnlinePlayersPacket();
    }
}
