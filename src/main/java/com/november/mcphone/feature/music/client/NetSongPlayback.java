package com.november.mcphone.feature.music.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.compat.client.NetMusicPlayback;
import com.november.mcphone.feature.music.NetSong;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端这边正在响的那些网络音乐 —— 按"谁在放"记着，好停得准。
 *
 * ================================================================
 * 为什么要记一份
 * ================================================================
 *
 * 因为要停。原版唱片那一支停起来简单：发一个 ClientboundStopSoundPacket，
 * 按音效 ID 停。可那个粒度只到"这一类声音"，旁边有人放同一张唱片也会被
 * 一起停掉（DiscService.stopSound 里写着这条）。
 *
 * 我们自己的声源没有这个限制，但前提是停的时候找得回那个实例 ——
 * SoundManager.stop 要的是实例本身，不是一个 ID。所以按实体 ID 记一份。
 *
 * ================================================================
 * 为什么在工厂里放进表，而不是 play 之后
 * ================================================================
 *
 * NetMusicPlayback.play 是异步的：它先去解析最终地址（可能要发网络请求），
 * 拿到之后才回主线程 new 出声源。所以 play 那一句返回时实例还不存在，
 * 只能在造它的那一刻顺手记下来。
 *
 * 那个工厂由 Minecraft.submitAsync 调度到主线程上跑，与本类其余方法
 * （网络包处理走 enqueueWork，同样在主线程）在同一条线程上，所以这张
 * 普通 HashMap 不需要加锁。
 */
public final class NetSongPlayback {

    private NetSongPlayback() {}

    /** 实体 ID → 正在响的那个声源 */
    private static final Map<Integer, NetSongSound> ACTIVE = new HashMap<>();

    /**
     * 某个人开始放一首网络歌。
     *
     * 先停掉他上一首：换碟就该从头来，不停的话两首会叠在一起 —— 那正是
     * 1.5.2 之前"同一张唱片响两遍"的乱音，不能再犯一次。
     */
    public static void start(int entityId, NetSong song) {
        stop(entityId);

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Entity source = mc.level.getEntity(entityId);
        if (source == null) {
            // 那个人不在这个客户端的视野里。不是错误：外放的半径按音量算，
            // 而实体的同步半径是另一回事，边缘上对不齐很正常
            return;
        }

        NetMusicPlayback.play(song, url -> {
            NetSongSound sound = new NetSongSound(source, url, song.seconds());
            ACTIVE.put(entityId, sound);
            return sound;
        });
    }

    /** 某个人停了。没在放则什么都不做 */
    public static void stop(int entityId) {
        NetSongSound sound = ACTIVE.remove(entityId);
        if (sound == null) return;

        Minecraft.getInstance().getSoundManager().stop(sound);
    }

    /**
     * 退出世界时全停。
     *
     * 不清的话，换到另一台服务器时上一台的歌还在响 —— 与聊天缓存那边
     * 同一个理由。而且实体 ID 在新世界里会指向别人。
     */
    public static void clear() {
        if (ACTIVE.isEmpty()) return;

        MCphone.LOGGER.debug("[MCphone] 退出世界，停掉 {} 条正在响的网络音乐", ACTIVE.size());
        for (NetSongSound sound : ACTIVE.values()) {
            Minecraft.getInstance().getSoundManager().stop(sound);
        }
        ACTIVE.clear();
    }
}
