package com.november.mcphone.feature.music.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.compat.client.NetMusicPlayback;
import com.november.mcphone.feature.music.NetSong;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端正在响的网络音乐，按实体 ID 记着 —— SoundManager.stop 要的是实例，不记就停不准。
 * NetMusicPlayback.play 是异步的，声源在工厂里造出来那一刻才进表；按实体记一个代次，
 * 工厂造出声源时对一下，变了说明解析地址期间玩家已经停了，当场掐掉。
 * 工厂由 submitAsync 调度到主线程，与其余方法同一条线程，HashMap 不用加锁。
 */
public final class NetSongPlayback {

    private NetSongPlayback() {}

    /** 实体 ID → 正在响的那个声源 */
    private static final Map<Integer, NetSongSound> ACTIVE = new HashMap<>();

    /** 实体 ID → 代次，每次开始或停止都 +1 */
    private static final Map<Integer, Integer> EPOCH = new HashMap<>();

    /** 先停掉他上一首，不停的话两首会叠在一起 */
    public static void start(int entityId, NetSong song) {
        final int epoch = stop(entityId);

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Entity source = mc.level.getEntity(entityId);
        if (source == null) {
            // 不在这个客户端的视野里。不是错误：外放半径与实体同步半径是两回事
            return;
        }

        NetMusicPlayback.play(song, url -> {
            NetSongSound sound = new NetSongSound(source, url, song.seconds());

            if (currentEpoch(entityId) != epoch) {
                // 解析地址那会儿他已经停了或换了一首，这一份作废，不进表
                sound.cancel();
                return sound;
            }

            ACTIVE.put(entityId, sound);
            return sound;
        });
    }

    /** 没在放则只推进代次；返回推进之后的代次，给 {@link #start} 认自己那一轮 */
    public static int stop(int entityId) {
        int epoch = EPOCH.merge(entityId, 1, Integer::sum);

        NetSongSound sound = ACTIVE.remove(entityId);
        if (sound != null) {
            Minecraft.getInstance().getSoundManager().stop(sound);
        }
        return epoch;
    }

    private static int currentEpoch(int entityId) {
        return EPOCH.getOrDefault(entityId, 0);
    }

    /** 声源自停时回调。按【实例】删而不是按实体 ID：那个人可能已经在放下一首了 */
    static void forget(NetSongSound sound) {
        ACTIVE.values().remove(sound);
    }

    /** 退出世界时全停：实体 ID 在新世界里会指向别人 */
    public static void clear() {
        if (ACTIVE.isEmpty() && EPOCH.isEmpty()) return;

        MCphone.LOGGER.debug("[MCphone] 退出世界，停掉 {} 条正在响的网络音乐", ACTIVE.size());
        for (NetSongSound sound : ACTIVE.values()) {
            Minecraft.getInstance().getSoundManager().stop(sound);
        }
        ACTIVE.clear();
        EPOCH.clear();
    }
}
