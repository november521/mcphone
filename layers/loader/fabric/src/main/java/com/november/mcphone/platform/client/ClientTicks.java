package com.november.mcphone.platform.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * 「每客户端刻」的门面 —— 全仓唯一碰 Fabric 客户端 tick 事件的地方。
 *
 * 语义与 NeoForge 那一支对齐：都挂在「刻结束时」，回调里不拿事件对象
 * （两个加载器的事件类型不同，那一个参数没有跨加载器的价值）。
 */
public final class ClientTicks {

    private ClientTicks() {}

    /** 注册一件每个客户端刻结束时要做的事。只在客户端调。 */
    public static void onEndTick(Runnable body) {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> body.run());
    }
}
