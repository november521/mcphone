package com.november.mcphone.compat.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.compat.NetMusicCompat;
import com.november.mcphone.feature.music.NetSong;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.function.Function;

/**
 * NetMusic 兼容层的【客户端】那一半 —— 拉流与起播。
 *
 * <h2>为什么与 NetMusicCompat 分成两个文件</h2>
 *
 * 那边读的是 CD 上的数据组件，两端都要用（服务端要判"这张能不能放"、
 * 要算时长）。这边碰的 {@code MusicPlayManager}、{@code NetMusicAudioStream}
 * 都在 NetMusic 的客户端包里，专用服务器上一碰就崩。
 *
 * 所以按端分开，本类放在含 {@code /client/} 的包里，与 MCEF 那边的做法一致
 * （见 build.gradle 里那段注释：所有引用都关在 client 包里，服务端一个字节
 * 都不会碰到）。
 *
 * <h2>为什么这里是反射，而 NetMusicCompat 是直接 import</h2>
 *
 * <b>因为这两个类改过包名。</b>NetMusic 1.2.x 把它们放在
 * {@code com.github.tartaricacid.netmusic.audio}，1.5.x 挪到了
 * {@code ...netmusic.client.audio}。写死任一个包名，另一个版本就是
 * {@code NoClassDefFoundError} —— 玩家看到的是"唱片放进槽位、点了播放没反应"，
 * 日志之外看不出是版本不对。
 *
 * 反射能把两代都认下来：按候选包名依次找类，找到哪个用哪个，都找不到就
 * 返回 false（外面本来就有"没装/不兼容"的分支）。
 *
 * <h2>代价：编译期不再校验</h2>
 *
 * 直接 import 时对方改签名我们编不过，当场发现；改成反射后这件事挪到了运行期。
 * 这是为跨版本兼容付的税。两代的方法签名与构造器完全一致
 * （{@code play(String, String, Function<URL, SoundInstance>)}、
 * {@code new NetMusicAudioStream(URL)}），所以一次查找两边通用。
 *
 * 找不到时只报一次日志：这条路每张网络 CD 都会走到，逐张刷"不兼容"会把日志淹掉。
 */
public final class NetMusicPlayback {

    private NetMusicPlayback() {}

    /** 1.5.x 起的包名 */
    private static final String PKG_NEW = "com.github.tartaricacid.netmusic.client.audio.";
    /** 1.2.x 的包名 */
    private static final String PKG_OLD = "com.github.tartaricacid.netmusic.audio.";

    private static volatile Method playMethod;
    private static volatile Class<?> streamClass;
    private static volatile boolean resolved;

    /**
     * 放一首网络歌。
     *
     * @param song      要放的歌
     * @param soundMaker 拿到最终地址后造一个声源。由调用方决定声音挂在哪儿
     *                   —— 手机外放要它跟着人走，那是我们的事，不是 NetMusic 的
     * @return 真的交出去了才返回 true；没装 NetMusic、或对方的 API 变了都返回 false
     */
    public static boolean play(NetSong song, Function<URL, SoundInstance> soundMaker) {
        if (!NetMusicCompat.isLoaded()) return false;

        try {
            Method play = playMethod();
            if (play == null) return false;
            play.invoke(null, song.url(), song.title(), soundMaker);
            return true;
        } catch (Throwable t) {
            // 兜 Throwable：对方改了类名或签名时抛的是 Error，见 NetMusicCompat
            MCphone.LOGGER.error("[MCphone] 交给 NetMusic 播放失败（版本可能不兼容）", t);
            return false;
        }
    }

    /**
     * 把一个地址打开成 Minecraft 认的音频流。
     *
     * 由声源在 getStream 里调用，那时已经在后台线程上 —— 这一句会真的去连
     * 网络，不能放在主线程。
     *
     * @throws Exception 连不上、格式不认识都从这里抛，由调用方兜住
     */
    public static AudioStream openStream(URL url) throws Exception {
        Class<?> clazz = streamClass();
        if (clazz == null) {
            throw new IllegalStateException("NetMusic 的音频流类没找到（版本可能不兼容）");
        }
        return (AudioStream) clazz.getConstructor(URL.class).newInstance(url);
    }

    // ---- 反射解析：两代包名依次试 ----

    private static Method playMethod() {
        resolve();
        return playMethod;
    }

    private static Class<?> streamClass() {
        resolve();
        return streamClass;
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;

        for (String pkg : new String[]{PKG_NEW, PKG_OLD}) {
            try {
                Class<?> manager = Class.forName(pkg + "MusicPlayManager");
                Method m = manager.getMethod("play", String.class, String.class, Function.class);
                Class<?> stream = Class.forName(pkg + "NetMusicAudioStream");

                playMethod = m;
                streamClass = stream;
                MCphone.LOGGER.info("[MCphone] NetMusic 兼容层就绪（{}）", pkg);
                return;
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                // 试下一代
            } catch (Throwable t) {
                MCphone.LOGGER.error("[MCphone] 解析 NetMusic 播放 API 出错（{}）", pkg, t);
            }
        }

        MCphone.LOGGER.warn("[MCphone] NetMusic 已装，但没找到认识的播放 API（试过 {} 与 {}）"
                + " —— 手机里的网络 CD 会放不出来", PKG_NEW, PKG_OLD);
    }
}
