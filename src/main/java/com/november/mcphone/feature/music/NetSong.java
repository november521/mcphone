package com.november.mcphone.feature.music;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import io.netty.buffer.ByteBuf;

/**
 * 一首网络歌曲 —— 从 NetMusic 的 CD 上读出来的那点信息。
 *
 * ================================================================
 * 为什么要有这么一个记录，而不是直接传 NetMusic 的 SongInfo
 * ================================================================
 *
 * 因为 NetMusic **没有对外的 API 包**：它的 SongInfo 是内部类
 * （{@code com.github.tartaricacid.netmusic.item.ItemMusicCD$SongInfo}）。
 * 让它出现在我们的网络包、服务端逻辑、界面里，等于把整个模组都绑在别人的
 * 内部结构上 —— 它改个字段名，我们到处都断，而且断的位置离原因很远。
 *
 * 所以那个类型只允许出现在 {@link com.november.mcphone.compat.NetMusicCompat}
 * 一个文件里。读出来立刻翻译成这个记录，别处一律只认它。真到了要跟进对方
 * 改动的那天，要改的就只有那一个方法。
 *
 * 这也是 dist 上的必要条件：ItemMusicCD 是两端都有的类，但把它的类型写进
 * 我们的包与服务端逻辑，会让"哪些类在专用服务器上会被加载"变得难以推断。
 *
 * ================================================================
 * 只留三个字段
 * ================================================================
 *
 * SongInfo 上还有 transName、vip、readOnly、artists。不抄过来不是嫌麻烦，
 * 是接触面越小越不容易断：
 *
 *   url     交给客户端去拉流的那个地址（可能还要 NetMusic 自己再解析一道）
 *   title   界面上显示什么
 *   seconds 时长。外放要靠它算"放到哪一刻为止"，与原版唱片的
 *           lengthInTicks 是同一个用途
 *
 * 真需要歌手名了再加，那时也只动兼容层那一个方法。
 *
 * @param url     歌曲地址。可能是最终地址，也可能要客户端再解析一次
 * @param title   歌名
 * @param seconds 时长（秒）。0 或负数表示不知道
 */
public record NetSong(String url, String title, int seconds) {

    /** 一首歌的名字最长多少字符。防的是伪造客户端塞一个超长字符串过来 */
    public static final int MAX_TITLE = 256;

    /** 地址最长多少字符。同上 */
    public static final int MAX_URL = 1024;

    public static final StreamCodec<ByteBuf, NetSong> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_URL), NetSong::url,
            ByteBufCodecs.stringUtf8(MAX_TITLE), NetSong::title,
            ByteBufCodecs.VAR_INT, NetSong::seconds,
            NetSong::new
    );

    /** 时长换成游戏刻，好与原版唱片的 lengthInTicks 用同一套算术 */
    public long lengthInTicks() {
        return Math.max(0L, (long) seconds * 20L);
    }
}
