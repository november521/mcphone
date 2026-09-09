package com.november.mcphone.compat.client;

import com.github.tartaricacid.netmusic.client.audio.MusicPlayManager;
import com.github.tartaricacid.netmusic.client.audio.NetMusicAudioStream;
import com.november.mcphone.MCphone;
import com.november.mcphone.compat.NetMusicCompat;
import com.november.mcphone.feature.music.NetSong;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;

import java.net.URL;
import java.util.function.Function;

/**
 * NetMusic 兼容层的【客户端】那一半 —— 拉流与起播。
 *
 * 为什么与 NetMusicCompat 分成两个文件
 *
 * 那边读的是 CD 上的数据组件，两端都要用（服务端要判"这张能不能放"、
 * 要算时长）。这边碰的 {@link MusicPlayManager}、{@link NetMusicAudioStream}
 * 都在 NetMusic 的 client 包里，专用服务器上一碰就崩。
 *
 * 所以按端分开，本类放在含 {@code /client/} 的包里，与 MCEF 那边的做法一致
 * （见 build.gradle 里那段注释：所有引用都关在 client 包里，服务端一个字节
 * 都不会碰到）。
 *
 * 整个模组里只有这两个文件允许出现 NetMusic 的类型
 *
 * 理由见 {@link NetMusicCompat} 的类注释：对方没有对外的 API 包，我们用的
 * 全是它的内部类。接触面越小，它改版时断的地方越少，而且断在哪儿一目了然。
 *
 * 这里用到的只有两样：
 *
 *   MusicPlayManager.play      解析最终地址（VIP、本地文件、404 都它处理），
 *                              然后在主线程上把我们给的声源交给 SoundManager
 *   new NetMusicAudioStream    把一个地址变成 Minecraft 认的 PCM 流。
 *                              m3u8、分块 http、各种编码都是它在扛，
 *                              我们自己那套 AudioDecoders 只认本地文件
 */
public final class NetMusicPlayback {

    private NetMusicPlayback() {}

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
            playInternal(song, soundMaker);
            return true;
        } catch (Throwable t) {
            // 兜 Throwable：对方改了类名或签名时抛的是 Error，见 NetMusicCompat
            MCphone.LOGGER.error("[MCphone] 交给 NetMusic 播放失败（版本可能不兼容）", t);
            return false;
        }
    }

    /**
     * 真正碰 NetMusic 的地方。单独一个方法，只在确认装了之后才可能被调到。
     *
     * play 是异步的：它内部先去解析最终地址（可能要发网络请求），拿到之后
     * 才回到主线程 new 出声源并交给 SoundManager。所以这一句返回时歌还没响，
     * 失败（404、地址解析不出来）也不会从这里抛出来 —— 对方会自己给玩家发
     * 一句聊天提示。这是它的行为，不是我们能改的，也不必改。
     */
    private static void playInternal(NetSong song, Function<URL, SoundInstance> soundMaker) {
        MusicPlayManager.play(song.url(), song.title(), soundMaker);
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
        return new NetMusicAudioStream(url);
    }
}
