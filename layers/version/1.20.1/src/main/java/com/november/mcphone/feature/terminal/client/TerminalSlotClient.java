package com.november.mcphone.feature.terminal.client;

import com.november.mcphone.feature.terminal.TerminalSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 收到卡槽同步包之后落到客户端玩家身上的那一手。
 *
 * 只为一件事存在：{@code TerminalNetworking} 是专用服务端也会加载的类（注册包要它），
 * 里面不能出现 {@code Minecraft}。把碰客户端类型的那一句挪进这个 {@code /client/} 包下的
 * 类，{@code verifyDistIsolation} 才放行。音乐那几个 S2C 包转 {@code DiscClientCache}，
 * 同一个理由。
 *
 * 不另存一份缓存，直接写回 {@link TerminalSlot}：客户端也有同一格玩家数据，让两端读的是
 * 同一个入口，{@code TerminalApp} 与 AE2 的 host 重建就都不用知道自己在哪一侧。
 */
public final class TerminalSlotClient {

    private TerminalSlotClient() {}

    /** 包还在路上时玩家可能已经离开世界了，那时 player 是 null，丢掉就是 */
    public static void apply(ItemStack terminal) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        TerminalSlot.applyFromServer(player, terminal);
    }
}
