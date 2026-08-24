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
 *
 * "失败"不止 play 返回 false 一种。还有一种更阴的：play 明明返回了 true，
 * 可那条流一个字节都没解出来（{@link LocalPlayback.Ending#SILENT}）。
 * 1.5.4 之前这种情况被当成"放完了"，而计数又恰好在 play 成功时清零，
 * 于是每 tick 换一首、永远转不完 —— 玩家报的"停不下来"就是它。
 * 现在计数只在一首【真的放完】时才清零。
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
     * 一首停下来了 —— 由 LocalPlayback 在通道停止时叫，带上停的原因。
     *
     * 三种原因的正确反应完全不同，见 {@link LocalPlayback.Ending}：
     *
     *   FINISHED  真放完了。清零失败计数，接下一首。与手动点「下一首」的
     *             区别只有一个：单曲循环模式下自动放完要再放一遍这一首，
     *             而手动点是真的换一首
     *   SILENT    打开成功却一声没出，等同于放不出来。当失败处理，让计数
     *             去兜底 —— 这一条就是 1.5.4 修的那个转不完的圈
     *   STARVED   缓冲喂不上。不是文件的问题，也不该往下转（转下去只会
     *             把一次卡顿变成一串各放两秒的半截歌）。LocalPlayback 已经
     *             记过日志并停下了，这里什么都不做
     */
    public static void onTrackEnded(LocalPlayback.Ending ending) {
        switch (ending) {
            case FINISHED -> {
                consecutiveFailures = 0;
                advance(false);
            }
            case SILENT -> {
                // 打开成功却一声没出。这不该发生 —— 帧头都自检过了 ——
                // 所以真出现时要留痕，别让它像 1.5.4 之前那样悄悄转圈
                noteProblem(current(),
                        Component.translatable("mcphone.music.problem.silent"),
                        "打开成功，但解码器一个字节都没出");
                failAndSkip();
            }
            case STARVED -> { }
        }
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

        if (openAndPlay(track)) return;
        failAndSkip();
    }

    /**
     * 这一首没成，跳去下一首；连续太多次就彻底停下。
     *
     * 【不】在 play() 成功时清零计数，只在一首真的放完时清（见
     * {@link #onTrackEnded}）。1.5.4 之前是在 play() 成功时清的，于是碰上
     * "打开成功但一声没出"的文件，计数每次都被重置，这个跳下一首的圈就
     * 永远转不完 —— 每秒 20 首，还停不下来。
     */
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
            if (stream == null) {
                // 音源没给流也没说原因，只能给一句笼统的
                noteProblem(track, Component.translatable("mcphone.music.problem.broken"),
                        "音源返回了空流");
                return false;
            }
            if (!LocalPlayback.play(stream, track.key())) return false;

            // 放成了，把上次的结论撤掉 —— 玩家可能刚把文件换成了能放的
            MusicProblems.clear(track);
            return true;
        } catch (IOException e) {
            noteProblem(track, reasonOf(e), e.getMessage());
            return false;
        }
    }

    /**
     * 记下这一首为什么没放出来：界面上标出来，日志里留详细的。
     *
     * 两处分开写，因为受众不同 —— 界面那一行只有一行字的宽度，而日志是
     * 排查用的，越详细越好。见 {@link UnplayableException}。
     */
    private static void noteProblem(Track track, Component reason, String detail) {
        MusicProblems.record(track, reason);
        MCphone.LOGGER.warn("[MCphone] 放不了 {}：{}",
                track == null ? "?" : track.key(), detail);
    }

    /** 能对玩家说清楚的就照说，说不清楚的（磁盘报错之类）给一句笼统的 */
    private static Component reasonOf(IOException e) {
        return e instanceof UnplayableException u ? u.reason()
                : Component.translatable("mcphone.music.problem.broken");
    }
}
