package com.november.mcphone.feature.music.net;

import net.minecraft.network.FriendlyByteBuf;
import com.november.mcphone.feature.music.NetSong;

/**
 * S2C：某个人的手机开始外放一首网络歌。发地址不发音频，每个客户端自己去拉。
 * 带实体 ID 而不是坐标：声音要跟着人走，与原版唱片那一支（绑在实体上）一致。
 */
public record PlayNetSongPacket(int entityId, NetSong song) {

    public static void encode(PlayNetSongPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId());
        NetSong.encode(msg.song(), buf);
    }

    public static PlayNetSongPacket decode(FriendlyByteBuf buf) {
        return new PlayNetSongPacket(
                buf.readVarInt(),
                NetSong.decode(buf));
    }
}
