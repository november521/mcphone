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
 * 手机外放网络音乐时的声源 —— 每 tick 把坐标贴到实体身上跟着人走（NetMusic 自己的声源钉在方块坐标上）。
 * 音频流走 NeoForge 可覆写的 getStream，{@link ModSounds#DISC_STREAM} 只是个壳；
 * 本类刻意不出现任何 NetMusic 类型，那一句关在 {@link NetMusicPlayback} 里。
 */
public final class NetSongSound extends AbstractTickableSoundInstance {

    /** 必须与原版唱片那一支（DiscService.VOLUME）一样响 */
    private static final float VOLUME = 4.0F;

    /** 到点之后再多放多久（游戏刻）：时长是 CD 上的整秒数，真实的流可能略长 */
    private static final int TAIL_TICKS = 50;

    private final Entity source;

    private final URL url;

    /** 放多少刻。0 表示不知道时长，一直放到流自己结束 */
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

    /** 自停时必须从表里摘掉，否则那张表会一直攥着 Entity；被外面停掉走的是另一条路，不会重复 */
    private void finish() {
        stop();
        NetSongPlayback.forget(this);
    }

    /** 还没开始就作废：工厂必须返回一个实例，所以造出来立刻掐掉。不走 finish()，这一份从没进过表 */
    void cancel() {
        stop();
    }

    private void follow() {
        this.x = source.getX();
        this.y = source.getY();
        this.z = source.getZ();
    }

    /**
     * 必须在后台线程上开：这一句会真的去连网络。
     * 开不出来抛 CompletionException，声源静静地不响，而不是把异常甩进渲染线程。
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
}
