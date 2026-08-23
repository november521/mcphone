package com.november.mcphone.feature.music.client.source;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.music.Track;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.JukeboxSong;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 原版唱片音源 —— 游戏里所有的唱片，包括别的模组加的。
 *
 * ================================================================
 * 为什么自己解码，而不是让游戏播
 * ================================================================
 *
 * 唱片的音频就在游戏资源里（{@code minecraft:sounds/records/cat.ogg} 这种），
 * 我们能直接读出来交给自己的播放核心。好处是唱片与本地文件享受同一套控制：
 * 暂停、继续、进度条、循环模式，一个都不少。
 *
 * 让 SoundEngine 去播的话就只有"播"和"停"两态——那正是 1.4.22 之前那个
 * 播放器的样子。
 *
 * 代价是这条路只有自己听得见（"耳机"）。要让周围人也听见，得走服务端播
 * 原版音效那条路，那是唱片仓要做的事，与这里并存不冲突。
 *
 * ================================================================
 * 曲目表来自数据包，所以必须进世界才有
 * ================================================================
 *
 * 1.21 起唱片不再是写死的 RecordItem，而是 JUKEBOX_SONG 这个【随存档同步
 * 的动态注册表】——数据包能加、能改、能删。所以主菜单里读不到，进了世界
 * 才有；而换个存档可能就是另一套。
 *
 * refresh 因此每次都重读，不缓存"已经读过了"这件事。
 *
 * ================================================================
 * 时长是白捡的
 * ================================================================
 *
 * JukeboxSong 自带 lengthInTicks()——唱片机就是靠它知道什么时候放完的。
 * 所以唱片这一档有真实的进度条，而本地文件没有（OGG 不读完整个文件拿不到
 * 时长）。
 */
public final class VanillaDiscSource implements MusicSource {

    /** 音源标识。会进播放记录，别改 */
    public static final String ID = "disc";

    /** 一 tick 20 分之一秒 */
    private static final long MS_PER_TICK = 50L;

    private List<Track> cached = List.of();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayNameKey() {
        return "mcphone.music.source.disc";
    }

    @Override
    public List<Track> list() {
        return cached;
    }

    @Override
    public void refresh() {
        Minecraft mc = Minecraft.getInstance();

        // 主菜单里没有世界，也就没有这份注册表。清空而不是保留上一个存档的
        // ——那套唱片未必属于这个存档
        if (mc.level == null) {
            cached = List.of();
            return;
        }

        List<Track> found = new ArrayList<>();
        mc.level.registryAccess().registry(Registries.JUKEBOX_SONG).ifPresent(reg -> {
            for (var entry : reg.entrySet()) {
                ResourceLocation key = entry.getKey().location();
                JukeboxSong song = entry.getValue();

                String title = song.description().getString();
                if (title.isEmpty()) title = key.getPath();

                found.add(new Track(ID, key.toString(), title,
                        song.lengthInTicks() * MS_PER_TICK, Track.Kind.LOCAL));
            }
        });

        found.sort(Comparator.comparing(Track::title));
        cached = List.copyOf(found);
    }

    /**
     * 把唱片解析成一条音频流。
     *
     * 三跳：唱片 → 音效事件 → sounds.json 里那条定义 → 资源包里的 ogg 文件。
     * 每一跳都可能断（数据包写了个不存在的音效、资源包删了那个文件），
     * 所以每一步都查一次，断了就记一行日志返回 null，由控制器跳过这一首。
     */
    @Override
    public AudioStream open(Track track) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;

        Optional<JukeboxSong> song = mc.level.registryAccess()
                .registry(Registries.JUKEBOX_SONG)
                .flatMap(reg -> reg.getOptional(ResourceLocation.parse(track.id())));
        if (song.isEmpty()) {
            MCphone.LOGGER.warn("[MCphone] 这个存档里没有这张唱片: {}", track.id());
            return null;
        }

        ResourceLocation soundId = song.get().soundEvent().value().getLocation();
        WeighedSoundEvents events = mc.getSoundManager().getSoundEvent(soundId);
        if (events == null) {
            MCphone.LOGGER.warn("[MCphone] 音效没有定义: {}", soundId);
            return null;
        }

        // 唱片的定义里只有一个音频，随机源给谁都一样；传 mc.level 的那一个
        // 省得自己造
        Sound sound = events.getSound(RandomSource.create());
        ResourceLocation file = sound.getPath();

        Optional<net.minecraft.server.packs.resources.Resource> res =
                mc.getResourceManager().getResource(file);
        if (res.isEmpty()) {
            MCphone.LOGGER.warn("[MCphone] 音频文件不在资源里: {}", file);
            return null;
        }

        // 原版音效一律是 ogg，直接交给 MC 自己的解码器。
        // 不走 AudioDecoders：那份名单是按【文件扩展名】认的，而这里手上
        // 是一条资源流，没有文件名可认
        InputStream in = new BufferedInputStream(res.get().open());
        return new JOrbisAudioStream(in);
    }
}
