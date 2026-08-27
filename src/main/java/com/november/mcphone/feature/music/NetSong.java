package com.november.mcphone.feature.music;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 一首网络歌曲，从 NetMusic 的 CD 上读出来的信息。
 * NetMusic 没有对外 API，它的 SongInfo 只允许出现在 NetMusicCompat 一个文件里，读出来立刻翻译成这个记录；
 * 网络包、服务端逻辑、界面一律只认它，dist 隔离也靠这条。
 *
 * @param url     歌曲地址，可能还要客户端再解析一次
 * @param seconds 时长（秒），0 或负数表示不知道
 */
public record NetSong(String url, String title, int seconds) {

    /** 字符串长度上限，防伪造客户端塞超长字符串 */
    public static final int MAX_TITLE = 256;

    public static final int MAX_URL = 1024;

    public static void encode(NetSong msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.url(), MAX_URL);
        buf.writeUtf(msg.title(), MAX_TITLE);
        buf.writeVarInt(msg.seconds());
    }

    public static NetSong decode(FriendlyByteBuf buf) {
        return new NetSong(
                buf.readUtf(MAX_URL),
                buf.readUtf(MAX_TITLE),
                buf.readVarInt());
    }

    /** 时长换成游戏刻，好与原版唱片的 lengthInTicks 用同一套算术 */
    public long lengthInTicks() {
        return Math.max(0L, (long) seconds * 20L);
    }
}
