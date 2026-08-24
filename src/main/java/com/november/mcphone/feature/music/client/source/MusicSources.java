package com.november.mcphone.feature.music.client.source;

import com.november.mcphone.feature.music.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * 音源名单 —— 曲库页看到的东西就是这里所有音源拼起来的。
 *
 * ================================================================
 * 加一个音源要改的全部东西
 * ================================================================
 *
 * 写一个 {@link MusicSource} 实现，在下面 SOURCES 里加一行。完。
 *
 * NetMusic 联动就走这条路：装了它就多一个 NetMusicSource，没装则那一行
 * 自己判断"对方不在场"返回空列表——与项目里 Waystones、Curios 的处理
 * 方式一致（见 WaystonesCompat 的类型隔离规矩，那条在这里同样适用：
 * 判断"装没装"和"真去调它"必须分在两个方法里）。
 *
 * ================================================================
 * 为什么现在不走 SPI
 * ================================================================
 *
 * App 与商店来源走 SPI，因为那是开给别的模组用的。音源眼下只有我们自己
 * 会加，一份看得见的名单比一份看不见的注册表好排查。等真有第三方要接，
 * 把这份名单换成 SpiLoader 是十几行的事，接口本身不用动。
 *
 * ================================================================
 * 原版唱片【不是】一个音源
 * ================================================================
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

    public static List<MusicSource> all() {
        return SOURCES;
    }

    /** 重扫所有音源。打开 App 或点刷新时调 */
    public static void refreshAll() {
        for (MusicSource s : SOURCES) s.refresh();
    }

    /** 所有音源的曲目拼成一张表，就是曲库页显示的顺序 */
    public static List<Track> allTracks() {
        List<Track> out = new ArrayList<>();
        for (MusicSource s : SOURCES) out.addAll(s.list());
        return List.copyOf(out);
    }

    /** 按曲目找回它的音源。播放时要靠它把曲目打开 */
    public static MusicSource of(Track track) {
        for (MusicSource s : SOURCES) {
            if (s.id().equals(track.sourceId())) return s;
        }
        return null;
    }
}
