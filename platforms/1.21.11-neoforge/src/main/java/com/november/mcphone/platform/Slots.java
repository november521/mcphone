package com.november.mcphone.platform;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 原版 {@link Slot} 上那几个跨版本换过签名的方法 —— 全仓唯一碰它们的地方。
 *
 * <h2>为什么值得一层</h2>
 *
 * {@code setByPlayer} 在 1.21 收两个参数（新栈 + 旧栈，用来算耐久与音效），
 * 1.20.1 只收新栈。方法名没变、签名变了 —— 这正是判据看不见的那一类：
 * 扫 import 看不见（它是原版类上的方法）、扫 1.20.5+ 的类型名也看不见。
 * {@code DiscBayMenu} 因此整份被挡在 {@code shared/} 之外，而两支之间真正的差别
 * <b>只有这一行</b>。
 *
 * 收进来之后那份菜单回到了 {@code shared/}，两支之间要改的只剩这个文件。
 *
 * <h2>这个包放什么</h2>
 *
 * {@code platform} 下装的是「各目标做同一件事、但写法不同」的东西。
 * <b>不要往这里放业务逻辑</b> —— 「转移物品之后要通知槽位」是业务，属于菜单；
 * 「这个版本的 setByPlayer 收几个参数」才是这里的事。
 */
public final class Slots {

    private Slots() {}

    /**
     * 玩家动过这一格之后通知槽位。
     *
     * @param slot     那一格
     * @param newStack 现在放进去的
     * @param oldStack 动之前的那一张 —— 这一支用得上，原版拿它算耐久与音效
     */
    public static void setByPlayer(Slot slot, ItemStack newStack, ItemStack oldStack) {
        slot.setByPlayer(newStack, oldStack);
    }
}
