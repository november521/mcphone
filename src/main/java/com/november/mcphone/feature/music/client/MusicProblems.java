package com.november.mcphone.feature.music.client;

import com.november.mcphone.feature.music.Track;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 哪几首放不出来、为什么 —— 曲库那一列据此把它们标出来。
 *
 * ================================================================
 * 为什么不在扫目录的时候就查
 * ================================================================
 *
 * 最直接的做法是 {@code LocalFileSource.refresh()} 里顺手把每个文件的头
 * 读一遍，列表上直接就标好。但那会把一次目录扫描变成一次全盘 IO：每打开
 * 一次 App 就要挨个开文件、跳过 ID3 标签（专辑封面动辄几百 KB）、找同步帧。
 * 音源接口的规矩写得很清楚——refresh 之外的一切都要便宜，list() 每帧都可能
 * 被问一次。
 *
 * 所以改成【放的时候才知道】：点了放不出来，就把原因记在这里，那一行从此
 * 变灰，停上去就告诉他为什么。信息在玩家真正需要的那一刻出现，而不打开
 * App 的人一分钱不花。
 *
 * ================================================================
 * 什么时候清
 * ================================================================
 *
 * 点「刷新」时清空：玩家多半就是换掉了那个文件才来点刷新的，还留着旧结论
 * 会让他以为没生效。退出世界不必单独清——这份东西按文件名记，与存档无关，
 * 而目录本来就是所有存档共用的。
 */
public final class MusicProblems {

    private MusicProblems() {}

    /** 曲目的 key → 一句给玩家看的原因 */
    private static final Map<String, Component> PROBLEMS = new HashMap<>();

    /** 记下这一首为什么放不出来。同一首再失败一次就覆盖，留最新的那条 */
    public static void record(Track track, Component reason) {
        if (track == null || reason == null) return;
        PROBLEMS.put(track.key(), reason);
    }

    /** 这一首上次为什么没放出来；没出过问题则返回 null */
    public static Component of(Track track) {
        return track == null ? null : PROBLEMS.get(track.key());
    }

    /** 这一首放成了，把旧结论撤掉 —— 玩家可能刚把文件换成了能放的 */
    public static void clear(Track track) {
        if (track != null) PROBLEMS.remove(track.key());
    }

    /** 全清。点刷新时调，理由见类注释 */
    public static void clearAll() {
        PROBLEMS.clear();
    }
}
