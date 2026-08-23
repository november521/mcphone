package com.november.mcphone.feature.music.client.playback;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import com.november.mcphone.MCphone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.IOException;

/**
 * 本地播放 —— 「耳机」那一半：只有自己听得见，但功能齐全。
 *
 * ================================================================
 * 为什么自己开一个音频设备
 * ================================================================
 *
 * 目标是真正的暂停、继续与进度，而原版的音效系统给不了：SoundEngine 只有
 * "播放"和"停止"，暂停是全局的（切菜单时那种），没有单条音轨的暂停，
 * 更没有播放位置。用它做音乐播放器，做出来就是个"点一下响、再点一下没"的
 * 东西——也就是这次要换掉的那个。
 *
 * 往下一层就够了：{@link Channel} 是 OpenAL 通道的薄封装，play / pause /
 * unpause / stop / setVolume 一应俱全。它由 {@link Library} 分配，而 Library
 * 的构造与 acquireChannel 都是公开的——所以不必开 AccessTransformer 去掏
 * 游戏自己那一个（项目至今刻意没开过 AT，见 build.gradle 里注释掉的那行）。
 *
 * 代价是多开一个 OpenAL 设备。换来的是：不碰游戏的音频状态、坏了也只坏
 * 我们自己这一摊、以及上面那些功能。
 *
 * ================================================================
 * 它只认一条流，不认文件
 * ================================================================
 *
 * play 收的是已经打开好的 {@link AudioStream}，不是路径。把"文件在哪、
 * 怎么解码"留给音源，这一层才可能被网络音源复用——那边根本没有路径这
 * 回事。id 只用来记日志和回答"现在放的是谁"。
 *
 * ================================================================
 * 音量必须自己乘游戏的滑块
 * ================================================================
 *
 * 我们的设备不归 SoundEngine 管，玩家把音乐音量拖到 0，这里照响——那是
 * 旧版本最招人烦的一条。所以每 tick 把【主音量 × 唱片音量 × 玩家在 App 里
 * 设的音量】乘进通道。三者缺一不可：主音量是总闸，唱片音量是"音乐类"，
 * App 里那个是这台手机自己的。
 *
 * ================================================================
 * 一切都在客户端主线程上
 * ================================================================
 *
 * OpenAL 的上下文不是线程安全的，跨线程调同一个 source 会出各种玄学问题。
 * 这里的每个方法都只从客户端线程调用：tick 事件、界面点击都是那个线程。
 * 解码也顺带在 tick 里做（updateStream 会读文件、解一秒的量）——本地文件
 * 够快，为它另开一条线程反而要处理同步。将来接网络音源时这条要重新考虑，
 * 那时读的是网络流，会卡住主线程。
 *
 * ================================================================
 * 放完了怎么知道
 * ================================================================
 *
 * 流读到尾时 read 返回一个空 buffer，Channel 就不再排队；已排的放完之后
 * 通道进入 stopped 状态。所以"stopped 即放完"。
 *
 * 理论上缓冲喂不上也会 stopped（underrun），但 attachBufferStream 一上来
 * 就排了 4 秒（核过字节码：pumpBuffers(4)，每格 1 秒），而我们每 tick
 * 泵一次 —— 要卡满 4 秒才可能误判，那时候游戏本身也已经卡死了。
 */
public final class LocalPlayback {

    private LocalPlayback() {}

    /** 播放状态。故意不用布尔量：三态用两个布尔量表达迟早会出现"既停又暂停" */
    public enum State { IDLE, PLAYING, PAUSED }

    private static Library library;
    private static Channel channel;
    private static AudioStream stream;

    /** 设备开不起来就永久放弃，不每次播放都重试一遍——那只会刷满日志 */
    private static boolean deviceFailed;

    private static State state = State.IDLE;

    /** 正在放的是谁（曲目的 key），只用于日志与界面回显 */
    private static String currentId;

    /** App 里那个音量，0..1。真正的输出还要乘游戏的两个滑块 */
    private static float volume = 1.0F;

    /** 已经放了多久。暂停时停止累加，所以不能直接拿开始时刻去减 */
    private static long elapsedMs;
    private static long lastResumeAt;

    /** 一首放完时叫一声。队列层装上它就能自动下一首 */
    private static Runnable finishListener = () -> {};

    // ============================================================
    //  控制
    // ============================================================

    /**
     * 从头播放一条流。会先把正在放的停掉。
     *
     * **流的所有权交给这里**：无论成功失败，调用方都不必再关它。这条规矩
     * 必须清楚，否则要么漏关（每首歌漏一个文件句柄），要么关两次。
     *
     * @param stream 已经打开好的 PCM 流，由音源提供
     * @param id     这首曲子的 key，只用于日志与回显
     * @return 真的开始放了才返回 true；设备开不起来、通道用光了都返回 false
     */
    public static boolean play(AudioStream stream, String id) {
        stop();

        if (stream == null) return false;
        if (!ensureDevice()) {
            closeQuietly(stream);
            return false;
        }

        Channel ch = library.acquireChannel(Library.Pool.STREAMING);
        if (ch == null) {
            // 通道池被占满。原版流式音效也从这个池子里拿，同时放太多就会没有
            MCphone.LOGGER.warn("[MCphone] 没有空闲的音频通道，这一首放不了: {}", id);
            closeQuietly(stream);
            return false;
        }

        // 不随位置衰减、也不跟着听者转：这是"耳机"，走到哪儿都一样响。
        // 不设的话声音会挂在世界原点，玩家一走远就听不见了
        ch.setRelative(true);
        ch.disableAttenuation();

        channel = ch;
        LocalPlayback.stream = stream;
        currentId = id;

        applyVolume();
        ch.attachBufferStream(stream);
        ch.play();

        state = State.PLAYING;
        elapsedMs = 0L;
        lastResumeAt = System.currentTimeMillis();
        return true;
    }

    /** 暂停。已经暂停或没在放时什么都不做 */
    public static void pause() {
        if (state != State.PLAYING || channel == null) return;

        channel.pause();
        elapsedMs += System.currentTimeMillis() - lastResumeAt;
        state = State.PAUSED;
    }

    /** 从暂停处继续 */
    public static void resume() {
        if (state != State.PAUSED || channel == null) return;

        channel.unpause();
        lastResumeAt = System.currentTimeMillis();
        state = State.PLAYING;
    }

    /** 停止并释放通道。可以随便调，没在放时什么都不做 */
    public static void stop() {
        if (channel != null) {
            channel.stop();
            library.releaseChannel(channel);
            channel = null;
        }
        closeQuietly(stream);
        stream = null;

        state = State.IDLE;
        currentId = null;
        elapsedMs = 0L;
    }

    /**
     * App 里的音量，0..1。
     *
     * 立刻生效：下一 tick 就会乘进通道，不必重新播放。
     */
    public static void setVolume(float v) {
        volume = Math.clamp(v, 0.0F, 1.0F);
    }

    public static float getVolume() {
        return volume;
    }

    /** 装一个"这首放完了"的回调。队列层用它自动下一首 */
    public static void setFinishListener(Runnable listener) {
        finishListener = listener == null ? () -> {} : listener;
    }

    // ============================================================
    //  查询
    // ============================================================

    public static State getState() {
        return state;
    }

    public static boolean isPlaying() {
        return state == State.PLAYING;
    }

    public static boolean isPaused() {
        return state == State.PAUSED;
    }

    /** 正在放（或暂停在）哪一首的 key；没有则返回 null */
    public static String currentId() {
        return currentId;
    }

    /** 已经放了多久。暂停期间不增长 */
    public static long elapsedMillis() {
        if (state == State.PLAYING) {
            return elapsedMs + (System.currentTimeMillis() - lastResumeAt);
        }
        return elapsedMs;
    }

    /** 音频设备可用吗。界面据此决定要不要提示"你的系统放不了音乐" */
    public static boolean isAvailable() {
        return !deviceFailed;
    }

    // ============================================================
    //  生命周期
    // ============================================================

    /**
     * 每 tick 泵一次流。
     *
     * 三件事：把播完的缓冲换成新的、发现放完了就收尾、把游戏音量的变化
     * 跟上。都很便宜，没在放的时候直接返回。
     */
    public static void onClientTick(ClientTickEvent.Post event) {
        if (state == State.IDLE || channel == null) return;

        if (state == State.PLAYING) {
            channel.updateStream();
            applyVolume();

            // stopped 即放完，理由见类注释
            if (channel.stopped()) {
                stop();
                finishListener.run();
            }
        }
    }

    /**
     * 退出世界或关游戏时释放设备。
     *
     * 必须释放：OpenAL 的设备是操作系统资源，不放的话每次进出世界都漏一个。
     * 下次播放会自动重新开一个。
     */
    public static void shutdown() {
        stop();
        if (library != null) {
            library.cleanup();
            library = null;
        }
        // 重置失败标记：这次开不起来不代表下次也开不起来（换了音频设备、
        // 插上耳机之类），重进世界给它一次机会
        deviceFailed = false;
    }

    // ============================================================
    //  内部
    // ============================================================

    /** 第一次播放时才开设备：不放音乐的玩家不必为此多占一个音频句柄 */
    private static boolean ensureDevice() {
        if (library != null) return true;
        if (deviceFailed) return false;

        try {
            Library lib = new Library();
            // 设备名传 null ＝ 跟随系统默认；不开 HRTF，那是给 3D 定位用的，
            // 而这是"耳机"，本来就不做空间化
            lib.init(null, false);
            library = lib;
            return true;
        } catch (Throwable t) {
            // 兜 Throwable 而不是 Exception：OpenAL 初始化失败会抛
            // UnsatisfiedLinkError 之类，那不是 Exception。放不了音乐是小事，
            // 为它把游戏拖崩是大事
            deviceFailed = true;
            MCphone.LOGGER.error("[MCphone] 音频设备打不开，本地音乐将不可用", t);
            return false;
        }
    }

    /** 玩家在 App 里设的 × 主音量 × 唱片音量。理由见类注释 */
    private static void applyVolume() {
        if (channel == null) return;

        Minecraft mc = Minecraft.getInstance();
        float master = mc.options.getSoundSourceVolume(SoundSource.MASTER);
        float records = mc.options.getSoundSourceVolume(SoundSource.RECORDS);
        channel.setVolume(volume * master * records);
    }

    private static void closeQuietly(AudioStream s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 关闭音频流失败: {}", e.toString());
        }
    }
}
