package com.november.mcphone.feature.terminal;

import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.feature.terminal.net.SyncTerminalSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;

/**
 * 终端卡槽那一格的同步 —— <b>只有 Forge 1.20.1 需要</b>。
 *
 * <h2>为什么单独一个类</h2>
 *
 * 这一支的能力没有 NeoForge 那种 {@code .sync()}：上线、重生、换维度这三处不各发一次包，
 * 客户端手里那一格就是空的。而这三个事件类型是<b>加载器专有</b>的
 * （{@code net.minecraftforge.event.entity.player.PlayerEvent}，NeoForge 那边包名不同），
 * 所以「什么时候发」留在这儿，「发完怎么写进去」是
 * {@link TerminalSlot#applyFromServer} 的事 —— 那个方法体只碰玩家数据，两个目标上都成立，
 * 已经收进共用层。
 *
 * 原先这三个方法长在 {@code TerminalSlot} 上，而那个类整个进了共用层 ——
 * 带着 Forge 的事件类型进去的话，NeoForge 那一支当场编不过。
 *
 * <h2>客户端为什么非知道不可</h2>
 *
 * 见 {@link TerminalSlot} 的类注释。
 */
public final class TerminalSlotSync {

    private TerminalSlotSync() {}

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        sync(event.getEntity());
    }

    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        sync(event.getEntity());
    }

    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        sync(event.getEntity());
    }

    private static void sync(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            MCphoneNetwork.sendToPlayer(serverPlayer, new SyncTerminalSlotPacket(TerminalSlot.get(player)));
        }
    }
}
