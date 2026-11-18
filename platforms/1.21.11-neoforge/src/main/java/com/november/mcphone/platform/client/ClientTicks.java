package com.november.mcphone.platform.client;

import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 每个客户端刻结束时做一件事。
 *
 * <h2>为什么要有这个门面</h2>
 *
 * 两支的「刻结束」不是同一个事件：这一支有专门的 {@code ClientTickEvent.Post}，
 * 1.20.1 那一支只有一个 {@code TickEvent.ClientTickEvent}，开头和结尾各派发一次，
 * 靠 {@code event.phase} 分辨。
 *
 * 分不出来的后果是<b>一刻做两次</b>，而不是编不过 —— 照这一支的写法搬过去，
 * 那边的队列会一刻发两张图、播放器一刻推进两次，症状是「快了一倍」，
 * 没有任何东西会报错。
 *
 * 收进来之后，上层写的是「每刻结束做什么」，一个事件类型都不必知道。
 */
public final class ClientTicks {

    private ClientTicks() {}

    /** 注册一件每个客户端刻结束时要做的事。只在客户端调。 */
    public static void onEndTick(Runnable body) {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> body.run());
    }
}
