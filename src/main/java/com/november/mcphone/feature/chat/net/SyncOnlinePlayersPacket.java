package com.november.mcphone.feature.chat.net;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;

/** S2C：在线玩家列表（已排除本人），截断到 MAX_PLAYERS；totalOnline 是真实总数，界面据此写出"显示前 N 人 / 共 M 人"。 */
public record SyncOnlinePlayersPacket(List<OnlinePlayer> players, int totalOnline) {

    public static final int MAX_PLAYERS = 200;

    public static void encode(SyncOnlinePlayersPacket msg, FriendlyByteBuf buf) {
        buf.writeCollection(msg.players(), (b, v) -> OnlinePlayer.encode(v, buf));
        buf.writeVarInt(msg.totalOnline());
    }

    public static SyncOnlinePlayersPacket decode(FriendlyByteBuf buf) {
        return new SyncOnlinePlayersPacket(
                buf.readCollection(n -> {
            if (n > MAX_PLAYERS) throw new DecoderException("列表超过上限 MAX_PLAYERS: " + n);
            return new java.util.ArrayList<>(n);
        }, b -> OnlinePlayer.decode(buf)),
                buf.readVarInt());
    }

}
