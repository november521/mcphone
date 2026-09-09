package com.november.mcphone.feature.terminal;

import com.november.mcphone.core.PhonePlayerData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 装在手机里的那台终端 —— 存取的唯一入口。
 *
 * 它不认牌子
 *
 * 里头放的就是一个 ItemStack，AE2 的无线终端、RS 的无线网格、Tom's 的高级无线终端都放得
 * 进来。"这台归谁管"由 {@link com.november.mcphone.feature.terminal.integration.Terminals}
 * 现问现答，不存在这里——存了就得考虑"玩家卸载了那个模组之后这一格里的记录怎么办"。
 *
 * 为什么是玩家 attachment，而不是手机物品上的 DataComponent
 *
 * 两个理由，第二个是硬的。
 *
 * 一、手机里的东西在这个模组里一律跟着玩家走：壁纸、笔记、聊天已读、唱片仓、买过的 App
 * 全在 {@link PhonePlayerData} 那一份里。装在手机里的终端是同一类东西。
 *
 * 二、<b>组件存不了它。</b>DataComponent 是不可变的，每次读出来是一份副本；而 AE2 与 RS
 * 都会往终端那个 ItemStack 上<b>写</b>——耗电、你在终端界面里调的排序与视图。它们每次都是
 * 拿"位置"回头现取（AE2 的 {@code ItemMenuHost.getItemStack()}、RS 的
 * {@code SlotReference.resolve()}），所以只要给的是副本，那些写入就落在副本上然后被丢掉：
 * 终端永远不掉电、设置每次都还原，而且<b>不报任何错</b>。附件里放的是同一个对象，写入落在
 * 真身上。
 */
public final class TerminalSlot {

    private TerminalSlot() {}

    /**
     * 手机里装的那台终端。没装则返回 {@link ItemStack#EMPTY}。
     *
     * 返回的是<b>活的</b>那一个，不是副本——存储模组往它上面写的耗电与设置要落在这里。
     * 别对返回值调 {@code copy()} 之后再交给它们。
     */
    public static ItemStack get(Player player) {
        return PhonePlayerData.of(player).terminal();
    }

    /** 换掉手机里那台终端。会同步给客户端 */
    public static void set(Player player, ItemStack stack) {
        PhonePlayerData.of(player).setTerminal(stack);
    }

    /**
     * 内容原地改过了，推一次同步。
     *
     * {@link #get} 给的是活对象，所以改它不会经过 {@link #set}，附件那边不知道自己变了。
     * 槽位改动之后要显式叫一次。
     */
    public static void markChanged(Player player) {
        set(player, get(player));
    }
}
