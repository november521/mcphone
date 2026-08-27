package com.november.mcphone.feature.music.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.ClientConfig;
import com.november.mcphone.feature.music.PlayMode;
import com.november.mcphone.feature.music.Track;
import com.november.mcphone.feature.music.client.playback.LocalPlayback;
import com.november.mcphone.feature.music.client.source.MusicSource;
import com.november.mcphone.feature.music.client.playback.UnplayableException;
import com.november.mcphone.feature.music.client.source.MusicSources;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.List;

/**
 * 播放控制器 —— 队列、循环模式、上一首下一首。界面读它，不自己记状态；LocalPlayback 只管一条音轨。
 * 队列是点播那一刻曲库的快照，不跟着目录刷新走。放不出来就跳下一首，连续失败太多次才停。
 */
public final class MusicController {

    private MusicController() {}

    private static final int MAX_CONSECUTIVE_FAILURES = 8;

    /** 空表示没在放 */
    private static List<Track> queue = List.of();

    /** 队列里的位置，-1 表示没有 */
    private static int index = -1;

    private static PlayMode mode = PlayMode.LIST_LOOP;

    private static final java.util.Random RANDOM = new java.util.Random();

    /** 只在一首真正放完时清零，【不】在 play() 成功时清：否则「打开成功却无声」的文件会让跳歌永远转不完 */
    private static int consecutiveFailures;

    public static void play(List<Track> tracks, int at) {
        if (tracks.isEmpty() || at < 0 || at >= tracks.size()) return;

        queue = List.copyOf(tracks);
        index = at;
        consecutiveFailures = 0;
        startCurrent();
    }

    /** 没在放就放当前这首，在放就暂停，暂停就继续 */
    public static void togglePlayPause() {
        switch (LocalPlayback.getState()) {
            case PLAYING -> LocalPlayback.pause();
            case PAUSED -> LocalPlayback.resume();
            case IDLE -> {
                if (current() != null) startCurrent();
            }
        }
    }

    /** 手动点的，所以单曲循环模式下也真的换一首 */
    public static void next() {
        advance(true);
    }

    /** 随机模式下也按队列顺序往回走：玩家点上一首是想回到刚才那首 */
    public static void previous() {
        if (queue.isEmpty()) return;

        consecutiveFailures = 0;
        index = index <= 0 ? queue.size() - 1 : index - 1;
        startCurrent();
    }

    public static void stop() {
        LocalPlayback.stop();
        index = -1;
        queue = List.of();
    }

    /** 正在放（或暂停在）哪一首；没有则 null */
    public static Track current() {
        return (index >= 0 && index < queue.size()) ? queue.get(index) : null;
    }

    public static PlayMode getMode() {
        return mode;
    }

    public static void setMode(PlayMode m) {
        if (m != null) mode = m;
    }

    public static PlayMode cycleMode() {
        mode = mode.next();
        ClientConfig.saveMusicMode(mode);
        return mode;
    }

    /**
     * 由 LocalPlayback 在通道停止时叫。FINISHED 接下一首（单曲循环则重放本首）；
     * SILENT 是打开成功却一声没出，当失败计数；STARVED 是缓冲喂不上，不是文件的问题，不往下转。
     */
    public static void onTrackEnded(LocalPlayback.Ending ending) {
        switch (ending) {
            case FINISHED -> {
                consecutiveFailures = 0;
                advance(false);
            }
            case SILENT -> {
                noteProblem(current(),
                        Component.translatable("mcphone.music.problem.silent"),
                        "打开成功，但解码器一个字节都没出");
                failAndSkip();
            }
            case STARVED -> { }
        }
    }

    private static void advance(boolean manual) {
        if (queue.isEmpty()) return;

        if (!manual && mode == PlayMode.SINGLE_LOOP) {
            startCurrent();
            return;
        }

        index = switch (mode) {
            case SINGLE_LOOP, LIST_LOOP -> (index + 1) % queue.size();
            case SHUFFLE -> randomOther();
        };
        startCurrent();
    }

    /** 随机挑一首，尽量不重复当前这首 */
    private static int randomOther() {
        if (queue.size() <= 1) return 0;

        int pick;
        do {
            pick = RANDOM.nextInt(queue.size());
        } while (pick == index);
        return pick;
    }

    private static void startCurrent() {
        Track track = current();
        if (track == null) return;

        if (openAndPlay(track)) return;
        failAndSkip();
    }

    private static void failAndSkip() {
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            MCphone.LOGGER.warn("[MCphone] 连续 {} 首都放不出来，停止播放",
                    consecutiveFailures);
            LocalPlayback.stop();
            consecutiveFailures = 0;
            return;
        }
        advance(false);
    }

    /** 只放 LOCAL 的；SHARED（外放）由服务端播，这道判断留作防线，别拿空流去喂音频通道 */
    private static boolean openAndPlay(Track track) {
        if (track.kind() != Track.Kind.LOCAL) return false;

        MusicSource source = MusicSources.of(track);
        if (source == null) {
            MCphone.LOGGER.warn("[MCphone] 找不到曲目的音源: {}", track.key());
            return false;
        }

        try {
            AudioStream stream = source.open(track);
            if (stream == null) {
                noteProblem(track, Component.translatable("mcphone.music.problem.broken"),
                        "音源返回了空流");
                return false;
            }
            if (!LocalPlayback.play(stream, track.key())) return false;

            MusicProblems.clear(track);
            return true;
        } catch (IOException e) {
            noteProblem(track, reasonOf(e), e.getMessage());
            return false;
        }
    }

    /** 界面上标一句给玩家看的，日志里留详细的 */
    private static void noteProblem(Track track, Component reason, String detail) {
        MusicProblems.record(track, reason);
        MCphone.LOGGER.warn("[MCphone] 放不了 {}：{}",
                track == null ? "?" : track.key(), detail);
    }

    private static Component reasonOf(IOException e) {
        return e instanceof UnplayableException u ? u.reason()
                : Component.translatable("mcphone.music.problem.broken");
    }
}
