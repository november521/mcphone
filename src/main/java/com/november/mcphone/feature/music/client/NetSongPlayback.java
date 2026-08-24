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
 * （网络包处理走 enqueueWork，同样在主线程）在同一条线程上，所以这两张
 * 普通 HashMap 不需要加锁。
 *
 * ================================================================
 * 但"异步"带来一个必须挡住的竞态
 * ================================================================
 *
 * 解析地址要时间（可能是一次网络请求）。这段时间里玩家完全可能已经把歌
 * 停了 —— 停止包到的时候表里还没有东西，stop 什么也没停着；之后工厂才把
 * 声源放进表并开始播。结果就是：**你停掉的歌照响，而且再按停止也没用**
 * （服务端认为没在放，那一按会被当成"播放"）。
 *
 * 所以按实体记一个【代次】：每次开始或停止都 +1。工厂造出声源时对一下
 * 代次，变了就说明这一轮已经作废，当场把它掐掉。
 */
public final class NetSongPlayback {

    private NetSongPlayback() {}

    /** 实体 ID → 正在响的那个声源 */
    private static final Map<Integer, NetSongSound> ACTIVE = new HashMap<>();

    /**
     * 实体 ID → 代次。每次开始或停止都 +1。
     *
     * 异步工厂靠它回答"我造出来的时候，这一轮还算数吗"，理由见类注释。
     * 一个人一个 int，退出世界时随 ACTIVE 一起清掉。
     */
    private static final Map<Integer, Integer> EPOCH = new HashMap<>();

    /**
     * 某个人开始放一首网络歌。
     *
     * 先停掉他上一首：换碟就该从头来，不停的话两首会叠在一起 —— 那正是
     * 1.5.2 之前"同一张唱片响两遍"的乱音，不能再犯一次。
     */
    public static void start(int entityId, NetSong song) {
        final int epoch = stop(entityId);

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

            if (currentEpoch(entityId) != epoch) {
                // 解析地址那会儿他已经停了、或者又换了一首。这一份作废 ——
                // 工厂必须返回一个实例，所以造出来立刻掐掉，也不进表
                sound.cancel();
                return sound;
            }

            ACTIVE.put(entityId, sound);
            return sound;
        });
    }

    /**
     * 某个人停了。没在放则只推进代次。
     *
     * @return 推进之后的代次，给 {@link #start} 用来认自己那一轮
     */
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

    /**
     * 一份自己放完停下来的声源，把它从表里摘掉。
     *
     * 由 {@link NetSongSound} 在自停时回调。不摘的话那一项会一直攥着声源、
     * 声源又攥着实体 —— 玩家早就退出、客户端世界也把他移除了，只有我们
     * 这张表还留着他。
     *
     * 按【实例】删而不是按实体 ID：那个人可能已经在放下一首了，按 ID 删会
     * 把新的那份误删。
     */
    static void forget(NetSongSound sound) {
        ACTIVE.values().remove(sound);
    }

    /**
     * 退出世界时全停。
     *
     * 不清的话，换到另一台服务器时上一台的歌还在响 —— 与聊天缓存那边
     * 同一个理由。而且实体 ID 在新世界里会指向别人。
     */
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
