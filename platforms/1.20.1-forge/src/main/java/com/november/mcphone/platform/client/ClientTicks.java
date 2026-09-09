package com.november.mcphone.platform.client;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

/**
 * 每个客户端刻结束时做一件事。
 *
 * <h2>为什么要有这个门面</h2>
 *
 * 两支的「刻结束」不是同一个事件：1.21 那一支有专门的 {@code ClientTickEvent.Post}，
 * 这一支只有一个 {@code TickEvent.ClientTickEvent}，开头和结尾各派发一次，
 * 靠 {@code event.phase} 分辨。
 *
 * 分不出来的后果是<b>一刻做两次</b>，而不是编不过 —— 照 1.21 那一支的写法搬过来，
 * 这边的队列会一刻发两张图、播放器一刻推进两次，症状是「快了一倍」，
 * 没有任何东西会报错。
 *
 * 收进来之后，上层写的是「每刻结束做什么」，一个事件类型都不必知道。
 */
public final class ClientTicks {

    private ClientTicks() {}

    /**
     * 注册一件每个客户端刻结束时要做的事。只在客户端调。
     *
     * <p>{@code Phase.END} 对应 1.21 那一支的 {@code ClientTickEvent.Post}。
     */
    public static void onEndTick(Runnable body) {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase != TickEvent.Phase.END) return;
            body.run();
        });
    }
}
