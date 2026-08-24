package com.november.mcphone.feature.music.client;

import com.november.mcphone.feature.music.DiscState;
import net.minecraft.world.item.ItemStack;

/**
 * 唱片仓在客户端的快照 —— 界面每帧从这里读，不发包。
 *
 * 与聊天那份缓存同一个道理：真值全在服务端，这里只是一份用来渲染的影子。
 *
 * 唯一一件界面要自己算的事是"到点没到点"：服务端下发的是外放的【终点刻】，
 * 而不是一个"在不在放"的布尔量。理由见 SyncDiscStatePacket ——
 * 服务端没有 tick 盯着唱片放完，布尔量会一直停在"在放"，玩家点那个键
 * 反倒把唱片从头又放一遍。终点是死的，拿现在的游戏刻一比就知道。
 *
 * 放在 client 包下是安全的：本类只被界面与网络处理函数的【客户端那一支】
 * 触及，而 ItemStack 是两端都有的类型。
 */
public final class DiscClientCache {

    private DiscClientCache() {}

    private static ItemStack disc = ItemStack.EMPTY;

    /** 外放放到哪一刻为止；{@link DiscState#NOT_PLAYING} 表示没在放 */
    private static long endsAtTick = DiscState.NOT_PLAYING;

    public static ItemStack getDisc() {
        return disc;
    }

    public static boolean hasDisc() {
        return !disc.isEmpty();
    }

    /**
     * 此刻还在外放吗。
     *
     * 收当前游戏刻而不是自己去问 Minecraft.getInstance()：本类会被专用
     * 服务器加载（网络包的注册与处理函数都在同一个类里引用它），碰一下
     * 客户端类型就会被 dist 校验当场拦下。取时间的那一步留在界面里做。
     *
     * @param nowTick 当前游戏刻，由界面从自己的世界里取
     */
    public static boolean isPlaying(long nowTick) {
        return endsAtTick != DiscState.NOT_PLAYING && nowTick < endsAtTick;
    }

    public static void set(ItemStack stack, long playingUntilTick) {
        disc = stack;
        endsAtTick = playingUntilTick;
    }

    /**
     * 退出世界时清空。
     *
     * 不清的话，换到另一个服务器时会先闪出上一台服务器里那张唱片
     * ——与聊天缓存同一个理由。
     */
    public static void clear() {
        disc = ItemStack.EMPTY;
        endsAtTick = DiscState.NOT_PLAYING;
    }
}
