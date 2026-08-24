package com.november.mcphone.feature.music.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.compat.client.NetMusicPlayback;
import com.november.mcphone.core.ModSounds;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 手机外放网络音乐时的那个声源 —— 跟着人走。
 *
 * ================================================================
 * 为什么不直接用 NetMusic 自己的声源
 * ================================================================
 *
 * 它那个（NetMusicSound）是钉在一个方块坐标上的 —— 它服务的是音乐播放器
 * 方块与大喇叭，摆在哪儿就从哪儿响，很合理。
 *
 * 可手机外放的整个设定是"扛着一台唱片机走"：原版唱片那一支用的是
 * {@code Level.playSound(玩家, 实体, ...)}，声音绑在人身上跟着他移动。
 * 网络音乐这一支要是钉在按下播放的那个坐标上，同一个唱片仓里两种唱片的
 * 行为就不一样了 —— 玩家不会认为这是两条实现路径，只会觉得坏了。
 *
 * 所以自己做一个：每 tick 把坐标更新到那个实体身上。
 *
 * ================================================================
 * 音频从哪儿来
 * ================================================================
 *
 * Minecraft 的音效系统只认注册过的音效事件加一个资源文件，给不了"凭空一条
 * 网络流"。NeoForge 为此把 {@code SoundInstance.getStream} 开成了可覆写的
 * ——覆写之后引擎就用你给的流（核过 SoundEngine，走的是
 * {@code soundinstance.getStream(...)} 那一支）。
 *
 * {@link ModSounds#DISC_STREAM} 就是为此存在的一个壳，它指向的那个原版
 * 音频文件一个字节都不会被播放，理由见那个类的注释。
 *
 * 真正把地址变成 PCM 的是 NetMusic（m3u8、分块 http、各种编码都是它在扛）。
 * 那一句关在 {@link NetMusicPlayback} 里 —— 本类刻意不出现任何 NetMusic 的
 * 类型，这样它改版时要跟进的地方仍然只有兼容层。
 */
public final class NetSongSound extends AbstractTickableSoundInstance {

    /**
     * 外放音量，与原版唱片那一支相同（见 DiscService.VOLUME）。
     *
     * 两支必须一样响：玩家换一张唱片就换一条实现路径，音量跟着变的话
     * 他只会觉得这个模组做坏了。
     */
    private static final float VOLUME = 4.0F;

    /**
     * 到点之后再多放多久（游戏刻）。
     *
     * 时长是从 CD 上读来的整秒数，而真实的流可能略长一点点（编码器补的
     * 静音帧之类）。掐太准会把结尾削掉一下，多留 50 刻（2.5 秒）不会有人
     * 察觉，反正流放完了自己就停。
     */
    private static final int TAIL_TICKS = 50;

    /** 声音挂在谁身上 */
    private final Entity source;

    private final URL url;

    /** 放多少刻。0 表示不知道时长，那就一直放到流自己结束 */
    private final int lifeTicks;

    private int ticks;

    public NetSongSound(Entity source, URL url, int seconds) {
        super(ModSounds.DISC_STREAM.get(), SoundSource.RECORDS,
                SoundInstance.createUnseededRandom());

        this.source = source;
        this.url = url;
        this.lifeTicks = Math.max(0, seconds) * 20;
        this.volume = VOLUME;
        this.looping = false;
        this.delay = 0;

        follow();
    }

    @Override
    public void tick() {
        if (Minecraft.getInstance().level == null || !source.isAlive()) {
            // 人没了（死亡、退出、切维度）就别再响。不停的话声音会僵在
            // 最后一个坐标上继续放完
            finish();
            return;
        }

        ticks++;
        if (lifeTicks > 0 && ticks > lifeTicks + TAIL_TICKS) {
            finish();
            return;
        }
        follow();
    }

    /**
     * 自己停下来，并从"正在响"的那张表里把自己摘掉。
     *
     * 摘这一下是必须的：那张表按实体 ID 记着声源，而声源攥着 {@link Entity}。
     * 歌自然放完之后没人来删的话，那个实体对象就一直被留着 —— 玩家早就退出
     * 了，客户端世界也把他移除了，只有我们这张表还攥着他。
     *
     * 被外面停掉（玩家按了停止）走的是另一条路，那边 remove 完才调
     * SoundManager.stop，不会重复。
     */
    private void finish() {
        stop();
        NetSongPlayback.forget(this);
    }

    /**
     * 还没开始就作废。
     *
     * 只有一种情况用得上：拉地址那会儿玩家已经把歌停了（见
     * {@link NetSongPlayback#start}）。造它的工厂必须返回一个实例，
     * 所以造出来立刻掐掉 —— 引擎下一 tick 看见 isStopped 就会把它收走。
     *
     * 不走 finish()：这一份从来没进过那张表，摘不摘都一样。
     */
    void cancel() {
        stop();
    }

    /** 把声源坐标贴到那个实体身上 */
    private void follow() {
        this.x = source.getX();
        this.y = source.getY();
        this.z = source.getZ();
    }

    /**
     * 引擎来要音频流。
     *
     * 必须在后台线程上开：这一句会真的去连网络，放在主线程上就是一次
     * 肉眼可见的卡顿，网慢的时候是几秒。Util.backgroundExecutor 正是
     * Minecraft 自己给这类事准备的池子。
     *
     * 开不出来时抛 CompletionException：引擎那边 thenAccept 收不到值，
     * 这个声源就静静地不响，而不是把异常甩进渲染线程。
     */
    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers,
                                                    Sound sound, boolean looping) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return NetMusicPlayback.openStream(url);
            } catch (Throwable t) {
                MCphone.LOGGER.warn("[MCphone] 网络音乐拉流失败: {} —— {}", url, t.toString());
                throw new CompletionException(t);
            }
        }, Util.backgroundExecutor());
    }

    /** 声音挂在谁身上 —— 停止时要按这个找回对应的声源 */
    public Entity source() {
        return source;
    }
}
