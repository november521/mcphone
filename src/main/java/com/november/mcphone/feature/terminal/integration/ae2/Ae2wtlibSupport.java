package com.november.mcphone.feature.terminal.integration.ae2;

import appeng.menu.locator.ItemMenuHostLocator;
import de.mari_023.ae2wtlib.api.terminal.ItemWT;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * AE2WTLib（AE2 无线终端）—— 全部对 {@code de.mari_023} 的引用只在这一个类里。
 *
 * 为什么非有它不可
 *
 * "AE2WTLib 的终端都继承 {@code WirelessTerminalItem}，所以调 {@code openFromInventory}
 * 就一网打尽"——这句话是错的，而且错得没有任何报错：
 *
 *   {@code WirelessTerminalItem.openFromInventory}  调 0 参的 {@code getMenuType()}
 *   {@code ItemWT.open}                             调 2 参的 {@code getMenuType(locator, player)}
 *
 * {@link ItemWT} 没有覆盖 0 参那个。它的菜单类型取决于是哪一种终端（通用终端还取决于
 * 玩家当前选的模式），只有 2 参那个知道。于是拿无线合成终端走 openFromInventory，
 * MenuOpener 会拿着 AE2 的<b>普通</b> ME 终端菜单类型去开——host 类型恰好又是兼容的，所以
 * 不崩、不报错，玩家只是看到一个没有合成格的终端界面。这是最难发现的一类错。
 *
 * 正确的调法是 {@link ItemWT#tryOpen}，它先查前置条件（电量之类）再按 2 参的分派开。
 *
 * 类加载纪律
 *
 * 这个类的方法签名里<b>没有</b>任何 {@code de.mari_023} 的类型，只有 ItemStack 与 AE2 的
 * locator。{@link Ae2Integration} 用 {@code ModPresence.isLoaded("ae2wtlib")} 判断之后再调，
 * invokestatic 的属主类是第一次执行到时才解析的，所以没装 AE2WTLib 的玩家永远不会加载到
 * 这个类，也就不会 NoClassDefFoundError。
 */
final class Ae2wtlibSupport {

    private Ae2wtlibSupport() {}

    /**
     * 这个物品是 AE2WTLib 的终端吗。
     *
     * @return true 表示它得走 {@link #open}，不能走 AE2 的 openFromInventory
     */
    static boolean isWirelessTerminal(ItemStack stack) {
        return stack.getItem() instanceof ItemWT;
    }

    /**
     * 按 AE2WTLib 自己的方式打开它。
     *
     * @return 开成功了没有。false 时 AE2WTLib 已经给玩家发过原因了
     */
    static boolean open(ServerPlayer player, ItemStack stack, ItemMenuHostLocator locator) {
        if (!(stack.getItem() instanceof ItemWT terminal)) return false;
        return terminal.tryOpen(player, locator, false);
    }
}
