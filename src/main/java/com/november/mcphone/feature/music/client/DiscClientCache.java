package com.november.mcphone.feature.music.client;

import net.minecraft.world.item.ItemStack;

/**
 * 唱片仓在客户端的快照 —— 界面每帧从这里读，不发包。
 *
 * 与聊天那份缓存同一个道理：真值全在服务端，这里只是一份用来渲染的影子。
 * 界面不自己推算"是不是还在放"——它不知道唱片有多长，服务端算好了下发。
 *
 * 放在 client 包下是安全的：本类只被界面与网络处理函数的【客户端那一支】
 * 触及，而 ItemStack 是两端都有的类型。
 */
public final class DiscClientCache {

    private DiscClientCache() {}

    private static ItemStack disc = ItemStack.EMPTY;
    private static boolean playing;

    public static ItemStack getDisc() {
        return disc;
    }

    public static boolean hasDisc() {
        return !disc.isEmpty();
    }

    public static boolean isPlaying() {
        return playing;
    }

    public static void set(ItemStack stack, boolean isPlaying) {
        disc = stack;
        playing = isPlaying;
    }

    /**
     * 退出世界时清空。
     *
     * 不清的话，换到另一个服务器时会先闪出上一台服务器里那张唱片
     * ——与聊天缓存同一个理由。
     */
    public static void clear() {
        disc = ItemStack.EMPTY;
        playing = false;
    }
}
