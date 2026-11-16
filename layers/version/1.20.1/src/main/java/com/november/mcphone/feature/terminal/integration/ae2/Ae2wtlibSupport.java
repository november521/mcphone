package com.november.mcphone.feature.terminal.integration.ae2;

import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.menu.locator.MenuLocator;
import de.mari_023.ae2wtlib.terminal.IUniversalWirelessTerminalItem;
import de.mari_023.ae2wtlib.terminal.ItemWT;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * AE2WTLib（AE2 无线终端）—— 全部对 {@code de.mari_023} 的引用只在这一个类里。
 *
 * <h2>为什么非有它不可</h2>
 *
 * "AE2WTLib 的终端都继承 {@code WirelessTerminalItem}，所以调 {@code openFromInventory}
 * 就一网打尽"——这句话是错的，而且错得没有任何报错：
 *
 * <pre>
 *   WirelessTerminalItem.openFromInventory       调 0 参的 getMenuType()
 *   IUniversalWirelessTerminalItem.tryOpen       调 1 参的 getMenuType(物品)
 * </pre>
 *
 * {@link ItemWT} 没有覆盖 0 参那个。它的菜单类型取决于是哪一种终端（通用终端还取决于
 * 玩家当前选的模式），只有 1 参那个知道。于是拿无线合成终端走 openFromInventory，
 * MenuOpener 会拿着 AE2 的<b>普通</b> ME 终端菜单类型去开——host 类型恰好又是兼容的，所以
 * 不崩、不报错，玩家只是看到一个没有合成格的终端界面。这是最难发现的一类错。
 *
 * 正确的调法是 {@link IUniversalWirelessTerminalItem#tryOpen}，它先查前置条件（电量之类）
 * 再按 1 参的分派开。
 *
 * <h2>15.x 与 19.x 的差别，就在包名和一个参数上</h2>
 *
 * <pre>
 *   19.x   de.mari_023.ae2wtlib.api.terminal.ItemWT#tryOpen(玩家, locator, 是否退回)
 *   15.x   de.mari_023.ae2wtlib.terminal.IUniversalWirelessTerminalItem#tryOpen(
 *              玩家, locator, 物品, 是否退回)
 * </pre>
 *
 * 15.x 没有单独的 api 子包（那是后来才拆出去的），也就没有单独的 {@code ae2wtlib_api}
 * 构件可编——所以这一支直接对整个模组 jar 编译，见 build.gradle。多出来的那个物品参数是
 * 因为 15.x 的 {@code getMenuType} 要看物品才知道开哪一种。
 *
 * <h2>类加载纪律</h2>
 *
 * 这个类的方法签名里<b>没有</b>任何 {@code de.mari_023} 的类型，只有 ItemStack、Player 与
 * AE2 的两个类型。调用方用 {@code ModList.isLoaded("ae2wtlib")} 判断之后再调，
 * invokestatic 的属主类是第一次执行到时才解析的，所以没装 AE2WTLib 的玩家永远不会加载到
 * 这个类，也就不会 NoClassDefFoundError。
 */
final class Ae2wtlibSupport {

    private Ae2wtlibSupport() {}

    /**
     * 这个物品是 AE2WTLib 的终端吗。
     *
     * 问的是 {@link ItemWT} 而不是那个接口：接口是给"能被通用终端合并进来"的东西实现的，
     * 别的模组也可以挂上去而不继承 ItemWT，那种情况下它未必吃得下我们给的 locator。
     * 认自己人认得窄一点，认不出来的退回 AE2 那条通路，最坏也只是开出普通 ME 终端。
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
    static boolean open(ServerPlayer player, ItemStack stack, MenuLocator locator) {
        if (!(stack.getItem() instanceof IUniversalWirelessTerminalItem terminal)) return false;
        return terminal.tryOpen(player, locator, stack, false);
    }

    /**
     * 让 AE2WTLib 自己造一个 menu host。
     *
     * {@link TerminalSlotLocator} 在客户端重建菜单时要交出一个 host，而 AE2WTLib 的终端得
     * 用它自己那个（{@code WTMenuHost} 一系）——AE2 的通用 host 里没有合成格、没有磁铁卡
     * 那些东西。它这个 getMenuHost 收 locator，正好就是我们缺的那个入口，
     * {@code CurioLocator} 用的也是它。
     */
    static ItemMenuHost menuHost(Player player, MenuLocator locator, ItemStack stack) {
        if (!(stack.getItem() instanceof IUniversalWirelessTerminalItem terminal)) return null;
        return terminal.getMenuHost(player, locator, stack);
    }
}
