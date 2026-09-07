package com.november.mcphone.core;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;

/**
 * 把手机上残留的"屏幕亮着"标记擦掉。<b>这个类在 NeoForge 那一支上不存在。</b>
 *
 * <h2>为什么这一支需要它</h2>
 *
 * 那边"屏幕亮着"是一个 {@code DataComponentType}，声明的时候只给了
 * {@code networkSynchronized}、<b>没给 persistent</b>：它描述的是"此刻有人正开着它"，
 * 不是手机自身的属性。玩家在开着手机时崩溃或断线，那部手机不会带着"亮着"存进存档——
 * 因为它压根不落盘。
 *
 * 1.20.1 没有这回事：物品数据就是 NBT，<b>同步与落盘是同一份</b>，想只同步不落盘没有
 * 任何办法。放着不管的后果正是那边刻意躲开的那个——开着手机崩一次，那部手机就永远亮着，
 * 而且谁也想不到要去关它。
 *
 * <h2>所以在两处补擦</h2>
 *
 * <ul>
 *   <li><b>下线</b>：正常退出、被踢、客户端崩溃、网线拔了，都走这里。这是主力。</li>
 *   <li><b>上线</b>：万一上一次是<b>服务端</b>崩的，下线事件没来得及跑，标记已经存进了
 *       存档。这一道把它擦掉。</li>
 * </ul>
 *
 * 只擦两只手，与服务端写它的地方（{@code NetworkHandler.handlePhoneScreenOn}）对齐——
 * 那一处也只往手上那部写。<b>擦不到的只剩一种</b>：服务端崩的那一刻，那部亮着的手机已经
 * 不在玩家手上了（被指令挪进箱子之类）。那种情况下箱子里那部会一直显示亮屏，直到有人把它
 * 拿在手上开一次手机再关掉。代价是一次贴图不对，不值得为它每 tick 去扫全服的容器。
 */
public final class PhoneScreenOnCleanup {

    private PhoneScreenOnCleanup() {}

    /** 由 MCphone 构造函数挂到 Forge 总线 */
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        clearHands(event.getEntity());
    }

    /** 同上 */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        clearHands(event.getEntity());
    }

    private static void clearHands(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            PhoneItemData.clearScreenOn(player.getItemInHand(hand));
        }
    }
}
