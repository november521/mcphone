package com.november.mcphone.feature.music.client;

import com.november.mcphone.feature.music.DiscState;
import net.minecraft.world.item.ItemStack;

/**
 * 唱片仓在客户端的快照 —— 界面每帧从这里读，不发包。
 * 服务端下发的是外放的【终点刻】而非布尔量（服务端没有 tick 盯着唱片放完），在不在放由界面拿当前刻比出来。
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

    /** 收 nowTick 而不自己问 Minecraft：本类会被专用服务器加载，碰客户端类型会被 dist 校验拦下 */
    public static boolean isPlaying(long nowTick) {
        return endsAtTick != DiscState.NOT_PLAYING && nowTick < endsAtTick;
    }

    public static void set(ItemStack stack, long playingUntilTick) {
        disc = stack;
        endsAtTick = playingUntilTick;
    }

    /** 退出世界时清空，否则换服务器会先闪出上一台的唱片 */
    public static void clear() {
        disc = ItemStack.EMPTY;
        endsAtTick = DiscState.NOT_PLAYING;
    }
}
