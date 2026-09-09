package com.november.mcphone.feature.chat.net;

import com.november.mcphone.core.net.Wire;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;

/** S2C：在线玩家列表（已排除本人），截断到 MAX_PLAYERS；totalOnline 是真实总数，界面据此写出"显示前 N 人 / 共 M 人"。 */
public record SyncOnlinePlayersPacket(List<OnlinePlayer> players, int totalOnline) {

    public static final int MAX_PLAYERS = 200;

    public static void encode(SyncOnlinePlayersPacket msg, FriendlyByteBuf buf) {
        Wire.writeList(buf, msg.players(), MAX_PLAYERS, OnlinePlayer::encode);
        buf.writeVarInt(msg.totalOnline());
    }

    public static SyncOnlinePlayersPacket decode(FriendlyByteBuf buf) {
        return new SyncOnlinePlayersPacket(
                Wire.readList(buf, MAX_PLAYERS, OnlinePlayer::decode),
                buf.readVarInt());
    }

}
