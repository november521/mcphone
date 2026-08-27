package com.november.mcphone.feature.music.client.playback;

import net.minecraft.util.Mth;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import com.november.mcphone.MCphone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCapabilities;

import javax.sound.sampled.AudioFormat;
import net.minecraftforge.event.TickEvent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * 本地播放 —— 「耳机」那一半：自己开一个 OpenAL 设备，换来原版 SoundEngine 给不了的单轨暂停/继续/进度。
 * OpenAL 的当前上下文是【进程级】的：碰 AL 之前必须用 {@link Scope} 换到我们的上下文、出作用域换回去，
 * 否则游戏的 SoundEngine 会打到我们的 source 上（两边编号都从 1 起）。
 * 所有方法只能在客户端主线程调：上下文不是线程安全的，解码也在 tick 里做。
 * 「通道停了」有三种意思（放完 / 从没出过声 / 缓冲喂不上），由 {@link TrackedStream} 区分，见 {@link Ending}。
 */
public final class LocalPlayback {

    private LocalPlayback() {}

    /** 故意不用布尔量：三态用两个布尔量表达迟早会出现「既停又暂停」 */
    public enum State { IDLE, PLAYING, PAUSED }

    /** 一首为什么停了，三种情况上层的正确反应完全不同 */
    public enum Ending {
        /** 流读到尾，而且真的出过声 —— 正常放完 */
        FINISHED,
        /** 一个字节都没出声就停了。解码器根本没工作，这首等于放不出来 */
        SILENT,
        /** 出过声，但流还没到尾通道就停了 —— 缓冲喂不上 */
        STARVED
    }

    private static Library library;
    private static Channel channel;
    private static TrackedStream stream;

    /** 我们自己那个上下文的句柄。0 ＝ 还没开起来 */
    private static long ourContext;

    /** 与上面配套的 LWJGL 能力表。两者必须一起换，见 {@link Scope} */
    private static ALCapabilities ourCaps;

    /** 设备开不起来就放弃，不每次播放都重试一遍刷日志 */
    private static boolean deviceFailed;

    private static State state = State.IDLE;

    /** 曲目的 key，只用于日志与界面回显 */
    private static String currentId;

    /** App 里那个音量，0..1。真正的输出还要乘游戏的两个滑块 */
    private static float volume = 1.0F;

    /** 已经放了多久。暂停时停止累加，所以不能直接拿开始时刻去减 */
    private static long elapsedMs;
    private static long lastResumeAt;

    /** 这一首总长，从流上问来的；{@link KnownDuration#UNKNOWN} 表示不知道 */
    private static long durationMs = KnownDuration.UNKNOWN;

    /** 一首停下来时叫一声，带上原因。队列层据此决定接不接下一首 */
    private static Consumer<Ending> endListener = e -> {};

    /**
     * 从头播放一条流，先把正在放的停掉。
     * 流的所有权交给这里：无论成功失败，调用方都不必再关它。
     * @return 真的开始放了才返回 true
     */
    public static boolean play(AudioStream stream, String id) {
        stop();

        if (stream == null) return false;
        if (!ensureDevice()) {
            closeQuietly(stream);
            return false;
        }

        try (Scope scope = new Scope()) {
            Channel ch = library.acquireChannel(Library.Pool.STREAMING);
            if (ch == null) {
                MCphone.LOGGER.warn("[MCphone] 没有空闲的音频通道，这一首放不了: {}", id);
                closeQuietly(stream);
                return false;
            }

            // 「耳机」：不随位置衰减。不设的话声音会挂在世界原点，一走远就听不见
            ch.setRelative(true);
            ch.disableAttenuation();

            // 必须在 attachBufferStream 之前包好：那一句就已经开始泵流了
            TrackedStream tracked = new TrackedStream(stream);

            channel = ch;
            LocalPlayback.stream = tracked;
            currentId = id;

            // 问原始的那一条：TrackedStream 不转发时长
            durationMs = stream instanceof KnownDuration known
                    ? known.durationMs() : KnownDuration.UNKNOWN;

            applyVolume();
            ch.attachBufferStream(tracked);
            ch.play();
        }

        state = State.PLAYING;
        elapsedMs = 0L;
        lastResumeAt = System.currentTimeMillis();
        return true;
    }

    public static void pause() {
        if (state != State.PLAYING || channel == null) return;

        try (Scope scope = new Scope()) {
            channel.pause();
        }
        elapsedMs += System.currentTimeMillis() - lastResumeAt;
        state = State.PAUSED;
    }

    public static void resume() {
        if (state != State.PAUSED || channel == null) return;

        try (Scope scope = new Scope()) {
            channel.unpause();
        }
        lastResumeAt = System.currentTimeMillis();
        state = State.PLAYING;
    }

    /**
     * 停止并释放通道，没在放时什么都不做。
     * 流被故意关两次：releaseChannel 的 destroy 会关挂上的流，但 attach 之前炸掉时没挂上，
     * closeQuietly 就是唯一的一次；close 幂等，关两次没有代价。
     */
    public static void stop() {
        if (channel != null) {
            try (Scope scope = new Scope()) {
                channel.stop();
                library.releaseChannel(channel);
            }
            channel = null;
        }
        closeQuietly(stream);
        stream = null;

        state = State.IDLE;
        currentId = null;
        elapsedMs = 0L;
        durationMs = KnownDuration.UNKNOWN;
    }

    /** App 里的音量，0..1，下一 tick 生效 */
    public static void setVolume(float v) {
        volume = Mth.clamp(v, 0.0F, 1.0F);
    }

    public static float getVolume() {
        return volume;
    }

    public static void setEndListener(Consumer<Ending> listener) {
        endListener = listener == null ? e -> {} : listener;
    }

    public static State getState() {
        return state;
    }

    public static boolean isPlaying() {
        return state == State.PLAYING;
    }

    /** 已经放了多久。暂停期间不增长 */
    public static long elapsedMillis() {
        if (state == State.PLAYING) {
            return elapsedMs + (System.currentTimeMillis() - lastResumeAt);
        }
        return elapsedMs;
    }

    /** 不知道则是 {@link KnownDuration#UNKNOWN}；OGG 一律不知道 */
    public static long durationMillis() {
        return durationMs;
    }

    public static boolean isAvailable() {
        return !deviceFailed;
    }

    /** 每 tick 泵一次流、跟上音量变化、发现停了就收尾 */
    // NeoForge 那边事件本身分 Pre/Post 两个类，订阅 ClientTickEvent.Post 就只收
    // 结束那一次。1.20.1 上只有一个 TickEvent.ClientTickEvent，Pre 和 Post 都从
    // 这里进来，靠 phase 字段区分 —— 【不判 phase 就会一 tick 触发两次】。
    // Post 对应 Phase.END
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (state != State.PLAYING || channel == null) return;

        Ending ending;
        try (Scope scope = new Scope()) {
            channel.updateStream();
            applyVolume();

            if (!channel.stopped()) return;

            // 必须先问停法再 stop() —— stop() 会把流丢掉
            ending = stream.ending();
        }

        String who = currentId;
        stop();

        if (ending == Ending.STARVED) {
            // 不往下转：不是文件的问题，转下去只会把一次卡顿变成一串半截歌
            MCphone.LOGGER.warn(
                    "[MCphone] 音频缓冲喂不上，停在这里了: {}（解码跟不上，"
                    + "或者游戏刚卡了一下超过 4 秒）", who);
        }
        endListener.accept(ending);
    }

    /** 退出世界或关游戏时释放设备：OpenAL 设备是操作系统资源，不放每次进出世界都漏一个 */
    public static void shutdown() {
        stop();
        if (library != null) {
            // cleanup 要在我们的上下文当前时做，Scope 退出时把游戏那个换回来
            try (Scope scope = new Scope()) {
                library.cleanup();
            }
            library = null;
            ourContext = 0L;
            ourCaps = null;
        }
        // 重置失败标记，重进世界再给设备一次机会
        deviceFailed = false;
    }

    /** 第一次播放时才开设备 */
    private static boolean ensureDevice() {
        if (library != null) return true;
        if (deviceFailed) return false;

        // init 会把当前上下文换成新开的这个，先记下进来时是哪个，无论成败都要放回去
        long prev = ALC10.alcGetCurrentContext();
        ALCapabilities prevCaps = capsOrNull();

        try {
            Library lib = new Library();
            // 设备名 null ＝ 跟随系统默认；不开 HRTF，「耳机」不做空间化
            lib.init(null, false);

            ourContext = ALC10.alcGetCurrentContext();
            ourCaps = AL.getCapabilities();

            // 非当前的上下文照样出声这一条各家实现写得不一样明白，显式说一句保险
            ALC10.alcProcessContext(ourContext);

            library = lib;
            return true;
        } catch (Throwable t) {
            // 兜 Throwable：OpenAL 初始化失败抛的是 UnsatisfiedLinkError 之类
            deviceFailed = true;
            ourContext = 0L;
            ourCaps = null;
            MCphone.LOGGER.error("[MCphone] 音频设备打不开，本地音乐将不可用", t);
            return false;
        } finally {
            ALC10.alcMakeContextCurrent(prev);
            AL.setCurrentProcess(prevCaps);
        }
    }

    /**
     * 把当前 OpenAL 上下文临时换成我们自己的，出了作用域换回【进来时那一个】——
     * 记死游戏那个会把已销毁的上下文塞回去。设备没开起来时是空操作。
     */
    private static final class Scope implements AutoCloseable {

        private final long prev;
        private final ALCapabilities prevCaps;
        private final boolean switched;

        Scope() {
            prev = ALC10.alcGetCurrentContext();
            prevCaps = capsOrNull();
            switched = ourContext != 0L && prev != ourContext;
            if (switched) {
                ALC10.alcMakeContextCurrent(ourContext);
                AL.setCurrentProcess(ourCaps);
            }
        }

        @Override
        public void close() {
            if (switched) {
                ALC10.alcMakeContextCurrent(prev);
                AL.setCurrentProcess(prevCaps);
            }
        }
    }

    /** 没设过能力表时 getCapabilities 会抛，那时当作"没有" */
    private static ALCapabilities capsOrNull() {
        try {
            return AL.getCapabilities();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /** 我们的设备不归 SoundEngine 管，得自己乘：App 里设的 × 主音量 × 唱片音量 */
    private static void applyVolume() {
        if (channel == null) return;

        Minecraft mc = Minecraft.getInstance();
        float master = mc.options.getSoundSourceVolume(SoundSource.MASTER);
        float records = mc.options.getSoundSourceVolume(SoundSource.RECORDS);
        channel.setVolume(volume * master * records);
    }

    /** 包在真正的流外面，记「出过声没有 / 读到尾没有」；只看不动，交给 Channel 的还是原 buffer */
    private static final class TrackedStream implements AudioStream {

        private final AudioStream inner;

        /** 0 表示这条流从头到尾没出过声 */
        private long delivered;

        /** read 交回空 buffer ＝ 读到尾了 */
        private boolean exhausted;

        TrackedStream(AudioStream inner) {
            this.inner = inner;
        }

        @Override
        public AudioFormat getFormat() {
            return inner.getFormat();
        }

        @Override
        public ByteBuffer read(int size) throws IOException {
            ByteBuffer buf = inner.read(size);
            if (buf == null || !buf.hasRemaining()) {
                exhausted = true;
                return buf;
            }
            delivered += buf.remaining();
            return buf;
        }

        @Override
        public void close() throws IOException {
            inner.close();
        }

        Ending ending() {
            if (delivered == 0L) return Ending.SILENT;
            return exhausted ? Ending.FINISHED : Ending.STARVED;
        }
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
