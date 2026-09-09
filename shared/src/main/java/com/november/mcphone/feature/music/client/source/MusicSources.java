package com.november.mcphone.feature.music.client.source;

import com.november.mcphone.feature.music.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * 音源名单 —— 曲库页看到的东西就是这里所有音源拼起来的。
 *
 * 加一个音源要改的全部东西
 *
 * 写一个 {@link MusicSource} 实现，在下面 SOURCES 里加一行。完。
 *
 * NetMusic 联动就走这条路：装了它就多一个 NetMusicSource，没装则那一行
 * 自己判断"对方不在场"返回空列表——与项目里 Waystones、Curios 的处理
 * 方式一致（见 WaystonesCompat 的类型隔离规矩，那条在这里同样适用：
 * 判断"装没装"和"真去调它"必须分在两个方法里）。
 *
 * 为什么现在不走 SPI
 *
 * App 与商店来源走 SPI，因为那是开给别的模组用的。音源眼下只有我们自己
 * 会加，一份看得见的名单比一份看不见的注册表好排查。等真有第三方要接，
 * 把这份名单换成 SpiLoader 是十几行的事，接口本身不用动。
 *
 * 原版唱片【不是】一个音源
 *
 * 1.4.30 到 1.5.1 之间它是——存档里 JUKEBOX_SONG 注册表的每一首都被塞进
 * 曲库。那是个错误：原版就有 19 张，装了模组的整合包里是几十上百张，
 * 玩家自己那几首歌被埋在里面找不着。
 *
 * 唱片有它自己的入口，就是界面顶上那条唱片仓：放一张进去，播的就是那一张。
 * 它走服务端播原版音效（周围人也听得见），本来就不需要客户端解码，
 * 也就不需要在这份名单里占一行。两件事各管各的。
 */
public final class MusicSources {

    private MusicSources() {}

    private static final List<MusicSource> SOURCES = List.of(
            new LocalFileSource()
    );

    /**
     * 拼好的那张表，缓存着。null 表示要重拼。
     *
     * 只在客户端线程上读写（界面绘制、点击、网络包处理都在那条线程），
     * 所以不加锁也不用 volatile。
     */
    private static List<Track> cached;

    /** 重扫所有音源。打开 App 或点刷新时调 */
    public static void refreshAll() {
        for (MusicSource s : SOURCES) s.refresh();
        cached = null;
    }

    /**
     * 所有音源的曲目拼成一张表，就是曲库页显示的顺序。
     *
     * 这张表必须缓存，因为界面每帧都问一次
     *
     * {@link MusicSource} 的接口注释写着"list() 必须便宜，界面每帧都可能
     * 问一次"。各音源确实守住了 —— 它们返回的是自己缓存好的表。可 1.5.19
     * 之前这个方法本身不便宜：每次都 new 一个 ArrayList、addAll 拷一遍，
     * 再 List.copyOf 又拷一遍。
     *
     * 于是 200 首歌的曲库，光是打开着音乐 App 站在那儿，每秒就要拷 2 万多
     * 次引用、分配一百多个数组 —— 全是垃圾，一帧都用不到第二次。
     *
     * 现在拼一次存着，{@link #refreshAll} 时失效。这也是各音源的 refresh
     * 必须只经由那个方法调用的原因：绕过去改了 list() 的内容，这里就发现
     * 不了。
     */
    public static List<Track> allTracks() {
        List<Track> tracks = cached;
        if (tracks != null) return tracks;

        List<Track> out = new ArrayList<>();
        for (MusicSource s : SOURCES) out.addAll(s.list());

        cached = List.copyOf(out);
        return cached;
    }

    /** 按曲目找回它的音源。播放时要靠它把曲目打开 */
    public static MusicSource of(Track track) {
        for (MusicSource s : SOURCES) {
            if (s.id().equals(track.sourceId())) return s;
        }
        return null;
    }
}
