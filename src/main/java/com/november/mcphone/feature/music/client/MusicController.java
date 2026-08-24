package com.november.mcphone.feature.music.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.ClientConfig;
import com.november.mcphone.feature.music.PlayMode;
import com.november.mcphone.feature.music.Track;
import com.november.mcphone.feature.music.client.playback.LocalPlayback;
import com.november.mcphone.feature.music.client.source.MusicSource;
import com.november.mcphone.feature.music.client.source.MusicSources;
import net.minecraft.client.sounds.AudioStream;

import java.io.IOException;
import java.util.List;
import java.util.RandomAccess;

/**
 * 播放控制器 —— 队列、循环模式、上一首下一首。界面读它，不自己记状态。
 *
 * ================================================================
 * 它与 LocalPlayback 的分工
 * ================================================================
 *
 * LocalPlayback 只管【一条音轨】：开始、暂停、继续、停止、音量。它不知道
 * 有列表这回事，也不知道"下一首"是什么意思。
 *
 * 本类管【顺序】：现在放到哪一首、放完去哪一首、上一首是谁。两件事分开，
 * 是因为将来唱片外放（服务端播）会换掉下面那一层，而顺序逻辑一行都不用改。
 *
 * ================================================================
 * 队列是快照，不是活的
 * ================================================================
 *
 * 玩家点某一首时，把【当时看到的那一列】整份记下来当队列。不实时跟着
 * 曲库走，是因为曲库会因为刷新目录而变——正听着歌，玩家往文件夹里丢了
 * 一首新的，"下一首"就跳到别处去了，而他什么都没做。
 *
 * ================================================================
 * 一首放不出来就跳过，但不能无限跳
 * ================================================================
 *
 * 文件坏了、格式其实不对、文件刚被删掉，play 都会返回 false。这时该自动
 * 试下一首——但如果整个目录的文件都坏了，那就成了一个转不完的圈，
 * 而且每转一圈都写一行日志。所以连续失败到一定次数就彻底停下。
 */
public final class MusicController {

    private MusicController() {}

    /**
     * 连续多少首放不出来就放弃。
     *
     * 取 8 而不是 3：正常人的目录里偶尔混几个坏文件很常见，跳过它们该是
     * 无感的。而 8 次之后还全是坏的，说明不是个别文件的问题。
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 8;

    /** 当前队列。空表示没在放 */
    private static List<Track> queue = List.of();

    /** 队列里的位置，-1 表示没有 */
    private static int index = -1;

    private static PlayMode mode = PlayMode.LIST_LOOP;

    /** 随机模式用。固定种子没有意义，这是"听歌"不是"存档" */
    private static final java.util.Random RANDOM = new java.util.Random();

    /** 连续失败计数，成功一次就归零 */
    private static int consecutiveFailures;

    // ============================================================
    //  播放
    // ============================================================

    /**
     * 播放一份列表里的第 index 首。
     *
     * @param tracks 整份列表，会被记成队列的快照
     */
    public static void play(List<Track> tracks, int at) {
        if (tracks.isEmpty() || at < 0 || at >= tracks.size()) return;

        queue = List.copyOf(tracks);
        index = at;
        consecutiveFailures = 0;
        startCurrent();
    }

    /**
     * 播放键：没在放就放当前这首，在放就暂停，暂停就继续。
     *
     * 一个键管三件事，与所有手机播放器一致——玩家不必先想"我现在是什么
     * 状态"再决定点哪儿。
     */
    public static void togglePlayPause() {
        switch (LocalPlayback.getState()) {
            case PLAYING -> LocalPlayback.pause();
            case PAUSED -> LocalPlayback.resume();
            case IDLE -> {
                if (current() != null) startCurrent();
            }
        }
    }

    /** 下一首。手动点的，所以单曲循环模式下也真的换一首 */
    public static void next() {
        advance(true);
    }

    /**
     * 上一首。
     *
     * 随机模式下也按队列顺序往回走，不"随机一首"——玩家点上一首是想
     * 回到刚才那首，随机回去等于又抽一次，那不是他要的。
     */
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

    // ============================================================
    //  状态
    // ============================================================

    /** 正在放（或暂停在）哪一首；没有则 null */
    public static Track current() {
        return (index >= 0 && index < queue.size()) ? queue.get(index) : null;
    }

    public static List<Track> currentQueue() {
        return queue;
    }

    public static int currentIndex() {
        return index;
    }

    public static PlayMode getMode() {
        return mode;
    }

    public static void setMode(PlayMode m) {
        if (m != null) mode = m;
    }

    /**
     * 循环模式按钮：点一下切下一种，顺手记进配置。
     *
     * 记住是必须的：玩家把它调成单曲循环，重进游戏又变回列表循环，
     * 那这个按钮等于每次开游戏都要重按一遍。
     */
    public static PlayMode cycleMode() {
        mode = mode.next();
        ClientConfig.saveMusicMode(mode);
        return mode;
    }

    // ============================================================
    //  内部
    // ============================================================

    /**
     * 一首放完了 —— 由 LocalPlayback 在音频流跑完时叫。
     *
     * 与手动点「下一首」的区别只有一个：单曲循环模式下，自动放完要再放
     * 一遍这一首，而手动点是真的换一首。
     */
    public static void onTrackFinished() {
        advance(false);
    }

    /**
     * @param manual true ＝ 玩家点的下一首，false ＝ 自动放完
     */
    private static void advance(boolean manual) {
        if (queue.isEmpty()) return;

        if (!manual && mode == PlayMode.SINGLE_LOOP) {
            startCurrent();
            return;
        }

        // 三种模式都不会走到列表外面：循环取模、随机在范围内挑。
        // 1.4.30 之前还有一个"放到头就停"的分支，那是顺序播放留下的，
        // 砍掉那一档之后它就是死代码了
        index = switch (mode) {
            case SINGLE_LOOP, LIST_LOOP -> (index + 1) % queue.size();
            case SHUFFLE -> randomOther();
        };
        startCurrent();
    }

    /** 随机挑一首，尽量不重复当前这首。只有一首歌时只能重复它 */
    private static int randomOther() {
        if (queue.size() <= 1) return 0;

        int pick;
        do {
            pick = RANDOM.nextInt(queue.size());
        } while (pick == index);
        return pick;
    }

    /**
     * 真正去放当前这一首。
     *
     * 打不开就自动试下一首，连续失败太多次才彻底停——理由见类注释。
     */
    private static void startCurrent() {
        Track track = current();
        if (track == null) return;

        if (openAndPlay(track)) {
            consecutiveFailures = 0;
            return;
        }

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

    /**
     * 把曲目交给对应的音源打开，再交给播放层。
     *
     * 只放 LOCAL 的。SHARED（外放）由服务端播，这一层拿不到也不需要那份
     * 音频数据——1.5.2 起曲库里根本不会出现 SHARED 的曲目（原版唱片已经
     * 从音源名单里摘掉，只在唱片仓那一条里出现），这道判断留着当防线：
     * 将来接 NetMusic 的外放时，忘了加分支会在这里安静地跳过，而不是
     * 拿一条空流去喂音频通道。
     */
    private static boolean openAndPlay(Track track) {
        if (track.kind() != Track.Kind.LOCAL) return false;

        MusicSource source = MusicSources.of(track);
        if (source == null) {
            MCphone.LOGGER.warn("[MCphone] 找不到曲目的音源: {}", track.key());
            return false;
        }

        try {
            AudioStream stream = source.open(track);
            if (stream == null) return false;
            return LocalPlayback.play(stream, track.key());
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 打开曲目失败 {}: {}", track.key(), e.toString());
            return false;
        }
    }
}
