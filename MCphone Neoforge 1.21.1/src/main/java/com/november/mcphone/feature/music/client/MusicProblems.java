package com.november.mcphone.feature.music.client;

import com.november.mcphone.feature.music.Track;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 哪几首放不出来、为什么 —— 曲库那一列据此把它们标灰。
 * 放的时候才记（扫目录时不读文件头，list() 必须便宜），点「刷新」时清空。
 */
public final class MusicProblems {

    private MusicProblems() {}

    /** 曲目的 key → 一句给玩家看的原因 */
    private static final Map<String, Component> PROBLEMS = new HashMap<>();

    public static void record(Track track, Component reason) {
        if (track == null || reason == null) return;
        PROBLEMS.put(track.key(), reason);
    }

    /** 没出过问题则返回 null */
    public static Component of(Track track) {
        return track == null ? null : PROBLEMS.get(track.key());
    }

    /** 放成了就把旧结论撤掉 */
    public static void clear(Track track) {
        if (track != null) PROBLEMS.remove(track.key());
    }

    /** 点刷新时调 */
    public static void clearAll() {
        PROBLEMS.clear();
    }
}
