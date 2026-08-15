package com.november.mcphone.core.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import com.november.mcphone.core.PhoneLocation;

/**
 * 快捷键开机。
 *
 * 手机收在背包里或挂在饰品槽上时，不必先切到手上——按一下键就打开。
 *
 * ============================================================
 * 与附属模组无关
 * ============================================================
 *
 * 这个键任何时候都有用：没装任何附属模组时，它从手上和背包里找手机；
 * 装了 Curios 才多找一个饰品槽。查找顺序与"手机在哪"的判定都在
 * PhoneLocation.find 里，那里没装 Curios 就直接跳过饰品这一步。
 *
 * ============================================================
 * 为什么在 tick 里读而不是监听按键事件
 * ============================================================
 *
 * consumeClick 会把积压的按下次数逐个取走，同一 tick 内连按几下不会丢，
 * 也不会重复触发——相机的快门键用的是同一套。按键事件那条路还得自己处理
 * "界面已经开着时不该再响应"，而 tick 里只要看一眼 mc.screen 就够了。
 */
public final class PhoneKeyHandler {

    private PhoneKeyHandler() {}

    /** 由 MCphoneClient 构造函数挂到游戏总线 */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        boolean pressed = false;
        // 无论如何都要把积压的点击取空，否则关掉界面后会补触发一次
        while (MCphoneKeyBindings.OPEN_PHONE.consumeClick()) pressed = true;

        if (!pressed || mc.player == null) return;

        // 已经开着别的界面（背包、聊天框、手机自己）就不抢——玩家正在那儿
        // 操作，凭空跳到手机界面只会打断他
        if (mc.screen != null) return;

        // 身上没有手机就什么都不做。不提示：按错键是很常见的事，
        // 为此弹一句"你没有手机"反而聒噪
        PhoneScreenOpener.open(mc.player);
    }
}
