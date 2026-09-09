package com.november.mcphone.feature.terminal.integration.ae2;

import appeng.items.tools.powered.WirelessTerminalItem;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.locator.MenuLocators;
import com.november.mcphone.feature.terminal.integration.TerminalIntegration;
import com.november.mcphone.feature.terminal.integration.TerminalSource;
import com.november.mcphone.platform.ModPresence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Applied Energistics 2 的接入 —— 无线终端，以及 AE2WTLib 的各种终端。
 *
 * 一个 instanceof 覆盖多少东西
 *
 * AE2 自己的无线终端、无线合成终端、无线样板访问终端，加上 AE2WTLib 的全部终端，都是
 * {@link WirelessTerminalItem} 的子类。所以认领只要一句 instanceof，不用去数有多少种，
 * 以后 AE2 加了新的也自动认得。
 *
 * 但"认得出"不等于"开得对"——AE2WTLib 那一支必须走它自己的入口，理由见
 * {@link Ae2wtlibSupport}。0.2.0 就栽在这上面。
 *
 * 电量、范围、维度、增幅卡
 *
 * 一条都不在这里。全在 AE2 自己的打开方法里，它自己查、自己拒绝、自己给玩家发消息。
 */
public final class Ae2Integration implements TerminalIntegration {

    public static final String MODID = "ae2";

    /**
     * 显示名。写死不查——要显示它的时候，那个模组多半正是没装的那一个。
     *
     * {@code TerminalApp} 的联动模组列表直接引用这个常量，这样"叫什么"全模组只有一份。
     * <b>引用它不会加载这个类</b>：带常量初始化式的 {@code static final String} 是编译期
     * 常量，javac 把它内联进调用方的常量池（{@code ldc}），运行时根本不碰 Ae2Integration。
     * 想改成非常量（比如拼字符串、查语言文件）之前先想清楚这一条——那会让没装 AE2 的玩家
     * 在构建 App 目录时加载到这个类。验一遍：
     * {@code javap -c TerminalApp | grep -A2 COMPANIONS}，看到 ldc 才对。
     */
    public static final String NAME = "Applied Energistics 2";

    /** AE2WTLib 的 modid。软依赖，判断它在不在场用这个常量 */
    private static final String AE2WTLIB_MODID = "ae2wtlib";

    @Override
    public String modId() {
        return MODID;
    }

    @Override
    public String displayName() {
        return NAME;
    }

    @Override
    public boolean claims(ItemStack stack) {
        return stack.getItem() instanceof WirelessTerminalItem;
    }

    /**
     * AE2 的终端一律能远程开——它本来就是无线的。
     *
     * 没电、超出范围、没绑定这些由 AE2 在打开时自己判断并给玩家发消息，不在这里预判：
     * 我们判断一次、它再判断一次，两边的规则迟早对不上，而对不上的表现是"手机上说没电、
     * 手里拿着又能开"。
     */
    @Override
    public boolean open(ServerPlayer player, ItemStack stack, TerminalSource source) {
        ItemMenuHostLocator locator = locatorFor(source);

        // AE2WTLib 那条必须先试：它的终端也是 WirelessTerminalItem，走下面那条会开出一个
        // 错误的菜单类型（普通 ME 终端，没有合成格），而且不报错。
        if (ModPresence.isLoaded(AE2WTLIB_MODID) && Ae2wtlibSupport.isWirelessTerminal(stack)) {
            return Ae2wtlibSupport.open(player, stack, locator);
        }

        if (!(stack.getItem() instanceof WirelessTerminalItem terminal)) return false;
        terminal.openFromInventory(player, locator);
        return true;
    }

    /** 把"在哪儿"翻译成 AE2 认的 locator。背包那一种是 AE2 自带的，卡槽那一种是我们注册的 */
    private static ItemMenuHostLocator locatorFor(TerminalSource source) {
        return source.map(
                ignored -> new TerminalSlotLocator(),
                inventorySlot -> MenuLocators.forInventorySlot(inventorySlot.index()));
    }

    @Override
    public void setup() {
        TerminalSlotLocator.register();
    }
}
