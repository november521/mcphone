package com.november.mcphone.feature.music.client.playback;

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
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * 本地播放 —— 「耳机」那一半：只有自己听得见，但功能齐全。
 *
 * 为什么自己开一个音频设备
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
 * 开完必须把上下文还回去
 *
 * OpenAL 的"当前上下文"是【进程级】的 —— alcMakeContextCurrent 连线程参数
 * 都没有，LWJGL 那一侧的 AL.createCapabilities 最后一句也是写一个 static
 * 字段。而 Library.init 里正好有这么一句。
 *
 * 于是第一次播放音乐之后，整个进程的 AL10.* 调用全部打到我们这个上下文上
 * ——【包括游戏自己的 SoundEngine】。而 source 编号是按上下文各记各的，两边
 * 都从 1 开始，于是游戏的通道 1 与我们的通道 1 就是同一个 source：
 *
 *   游戏播个音效 → alSourcePlay(1) → 播的是我们暂停着的那首歌
 *   游戏停个音效 → alSourceStop(1) → 停的是我们正放着的那首歌
 *
 * 玩家看到的是"暂停了还时不时响几秒"（响的正是排在队里那 4 个 1 秒的缓冲），
 * 以及两路声音打在同一个 source 上的乱音。1.7.6 之前一直是这样，而且只在
 * 播过一次音乐之后才出现 —— 这是它藏了这么久的原因。
 *
 * 所以每次碰 AL 之前用 {@link Scope} 换到我们的上下文，出了作用域立刻换回
 * 进来时那个。设备照旧单独开着，变的只是不再把当前上下文一直霸占住。
 *
 * 它只认一条流，不认文件
 *
 * play 收的是已经打开好的 {@link AudioStream}，不是路径。把"文件在哪、
 * 怎么解码"留给音源，这一层才可能被网络音源复用——那边根本没有路径这
 * 回事。id 只用来记日志和回答"现在放的是谁"。
 *
 * 音量必须自己乘游戏的滑块
 *
 * 我们的设备不归 SoundEngine 管，玩家把音乐音量拖到 0，这里照响——那是
 * 旧版本最招人烦的一条。所以每 tick 把【主音量 × 唱片音量 × 玩家在 App 里
 * 设的音量】乘进通道。三者缺一不可：主音量是总闸，唱片音量是"音乐类"，
 * App 里那个是这台手机自己的。
 *
 * 一切都在客户端主线程上
 *
 * OpenAL 的上下文不是线程安全的，跨线程调同一个 source 会出各种玄学问题。
 * 这里的每个方法都只从客户端线程调用：tick 事件、界面点击都是那个线程。
 * 解码也顺带在 tick 里做（updateStream 会读文件、解一秒的量）——本地文件
 * 够快，为它另开一条线程反而要处理同步。将来接网络音源时这条要重新考虑，
 * 那时读的是网络流，会卡住主线程。
 *
 * "通道停了"有三种意思，必须分开
 *
 * 1.5.4 之前这里写的是"stopped 即放完"，理由是 attachBufferStream 一上来
 * 就排了 4 秒（核过字节码：pumpBuffers(4)，每格 1 秒），要卡满 4 秒才会
 * 误判。那个推理漏了一整类情况 —— 通道也可能【从来没响过】就 stopped：
 * 解码器一个字节都没解出来时，排进去的全是空 buffer，alSourcePlay 之后
 * 立刻就是 stopped。
 *
 * 而那时 play() 返回的是 true，于是 MusicController 把连续失败计数清零，
 * 下一 tick 又 stopped、又"放完了"、又换一首……每秒 20 首地转下去，
 * 转不完，也停不下来。这正是玩家报的"乱音，还停不下来"。
 *
 * 所以现在把流包一层（{@link TrackedStream}），记两件事：出过声没有、
 * 是不是真读到尾了。三种停法分别处理：
 *
 *   读到尾 + 出过声   → 正常放完，通知上层接下一首
 *   一个字节都没出声  → 这首根本放不出来，当失败上报，由失败计数兜底
 *   没读到尾但出过声  → 缓冲喂不上（underrun），停下并说明，【不】自动
 *                       往下转 —— 转下去只会把一次卡顿变成一串半截歌
 */
public final class LocalPlayback {

    private LocalPlayback() {}

    /** 播放状态。故意不用布尔量：三态用两个布尔量表达迟早会出现"既停又暂停" */
    public enum State { IDLE, PLAYING, PAUSED }

    /**
     * 一首为什么停了。理由见类注释 —— 上层要靠它决定接下来做什么，
     * 三种情况的正确反应完全不同。
     */
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

    /**
     * 这一首总共有多长。{@link KnownDuration#UNKNOWN} 表示不知道。
     *
     * 从流上问来的，不是从曲目上：本地文件的时长要打开文件才算得出来，
     * 而扫目录时不许开文件（理由见 KnownDuration）。
     */
    private static long durationMs = KnownDuration.UNKNOWN;

    /** 一首停下来时叫一声，带上原因。队列层据此决定接不接下一首 */
    private static Consumer<Ending> endListener = e -> {};

    //  控制

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

        try (Scope scope = new Scope()) {
            Channel ch = library.acquireChannel(Library.Pool.STREAMING);
            if (ch == null) {
                // 通道池被占满。这个池子是我们自己那个设备的，与游戏的不共用
                MCphone.LOGGER.warn("[MCphone] 没有空闲的音频通道，这一首放不了: {}", id);
                closeQuietly(stream);
                return false;
            }

            // 不随位置衰减、也不跟着听者转：这是"耳机"，走到哪儿都一样响。
            // 不设的话声音会挂在世界原点，玩家一走远就听不见了
            ch.setRelative(true);
            ch.disableAttenuation();

            // 包一层来记"出过声没有 / 读到尾没有"，理由见类注释。
            // 必须在 attachBufferStream 之前包好：那一句就已经开始泵流了
            TrackedStream tracked = new TrackedStream(stream);

            channel = ch;
            LocalPlayback.stream = tracked;
            currentId = id;

            // 问原始的那一条，不是包好的：TrackedStream 只管记有没有出过声，
            // 不转发时长
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

    /** 暂停。已经暂停或没在放时什么都不做 */
    public static void pause() {
        if (state != State.PLAYING || channel == null) return;

        try (Scope scope = new Scope()) {
            channel.pause();
        }
        elapsedMs += System.currentTimeMillis() - lastResumeAt;
        state = State.PAUSED;
    }

    /** 从暂停处继续 */
    public static void resume() {
        if (state != State.PAUSED || channel == null) return;

        try (Scope scope = new Scope()) {
            channel.unpause();
        }
        lastResumeAt = System.currentTimeMillis();
        state = State.PLAYING;
    }

    /** 停止并释放通道。可以随便调，没在放时什么都不做 */
    public static void stop() {
        if (channel != null) {
            try (Scope scope = new Scope()) {
                channel.stop();
                // releaseChannel 会 destroy 通道：删 source、顺带关掉挂着的流
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

    /** 装一个"这首停了"的回调，参数是停的原因。队列层用它决定下一步 */
    public static void setEndListener(Consumer<Ending> listener) {
        endListener = listener == null ? e -> {} : listener;
    }

    //  查询

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

    /**
     * 这一首总共有多长；不知道则是 {@link KnownDuration#UNKNOWN}。
     *
     * OGG 一律不知道 —— 走的是 Minecraft 自己的解码器，问不到。
     */
    public static long durationMillis() {
        return durationMs;
    }

    /** 音频设备可用吗。界面据此决定要不要提示"你的系统放不了音乐" */
    public static boolean isAvailable() {
        return !deviceFailed;
    }

    //  生命周期

    /**
     * 每 tick 泵一次流。
     *
     * 三件事：把播完的缓冲换成新的、发现放完了就收尾、把游戏音量的变化
     * 跟上。都很便宜，没在放的时候直接返回。
     */
    public static void onClientTick(ClientTickEvent.Post event) {
        if (state != State.PLAYING || channel == null) return;

        Ending ending;
        try (Scope scope = new Scope()) {
            channel.updateStream();
            applyVolume();

            if (!channel.stopped()) return;

            // 先问清楚是哪一种停法，再 stop() —— stop() 会把流丢掉
            ending = stream.ending();
        }

        String who = currentId;
        stop();

        if (ending == Ending.STARVED) {
            // 不往下转：这不是文件的问题，是这一刻喂不上。转下去只会把
            // 一次卡顿变成一串各放两秒的半截歌
            MCphone.LOGGER.warn(
                    "[MCphone] 音频缓冲喂不上，停在这里了: {}（解码跟不上，"
                    + "或者游戏刚卡了一下超过 4 秒）", who);
        }
        endListener.accept(ending);
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
            // cleanup 先销毁通道（要我们的上下文当前），再销毁上下文本身。
            // Scope 出作用域时把游戏那个换回来 —— 游戏关自己的引擎走的也是
            // 这条路，所以"销毁当前上下文"这一步是走得通的
            try (Scope scope = new Scope()) {
                library.cleanup();
            }
            library = null;
            ourContext = 0L;
            ourCaps = null;
        }
        // 重置失败标记：这次开不起来不代表下次也开不起来（换了音频设备、
        // 插上耳机之类），重进世界给它一次机会
        deviceFailed = false;
    }

    //  内部

    /** 第一次播放时才开设备：不放音乐的玩家不必为此多占一个音频句柄 */
    private static boolean ensureDevice() {
        if (library != null) return true;
        if (deviceFailed) return false;

        // init 里那句 alcMakeContextCurrent 会把当前上下文换成新开的这个，
        // 所以先记下进来时是哪个，无论成败都要放回去（见类注释）
        long prev = ALC10.alcGetCurrentContext();
        ALCapabilities prevCaps = capsOrNull();

        try {
            Library lib = new Library();
            // 设备名传 null ＝ 跟随系统默认；不开 HRTF，那是给 3D 定位用的，
            // 而这是"耳机"，本来就不做空间化
            lib.init(null, false);

            ourContext = ALC10.alcGetCurrentContext();
            ourCaps = AL.getCapabilities();

            // 非当前的上下文照样出声，但这一条各家实现写得不一样明白。
            // 显式说一句"继续处理"，一行的保险
            ALC10.alcProcessContext(ourContext);

            library = lib;
            return true;
        } catch (Throwable t) {
            // 兜 Throwable 而不是 Exception：OpenAL 初始化失败会抛
            // UnsatisfiedLinkError 之类，那不是 Exception。放不了音乐是小事，
            // 为它把游戏拖崩是大事
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
     * 把当前 OpenAL 上下文临时换成我们自己的，出了作用域立刻换回去。
     *
     * 换回的是【进来时那一个】，不是记死的游戏那一个：游戏重载音频引擎
     * （玩家换音频设备、重载资源包）时会另建一个上下文，记死了就会把已经
     * 销毁的那个塞回去。
     *
     * 设备还没开起来时整个是空操作 —— 没有上下文可换，也没有 AL 要调。
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

    /** 玩家在 App 里设的 × 主音量 × 唱片音量。理由见类注释 */
    private static void applyVolume() {
        if (channel == null) return;

        Minecraft mc = Minecraft.getInstance();
        float master = mc.options.getSoundSourceVolume(SoundSource.MASTER);
        float records = mc.options.getSoundSourceVolume(SoundSource.RECORDS);
        channel.setVolume(volume * master * records);
    }

    /**
     * 包在真正的流外面，记两件事：一共交出去多少字节、以及有没有读到尾。
     *
     * 只看不动：{@link ByteBuffer#remaining()} 不改变 position，交给
     * Channel 的还是原封不动那一个 buffer。
     */
    private static final class TrackedStream implements AudioStream {

        private final AudioStream inner;

        /** 一共交出去多少字节。0 表示这条流从头到尾没出过声 */
        private long delivered;

        /** 读到尾了 —— read 交回一个空 buffer 就是这个意思 */
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

        /** 通道停了，那是哪一种停法。判断顺序有讲究，见 Ending 的注释 */
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
