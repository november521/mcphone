package com.november.mcphone.feature.terminal.integration.ae2;

import appeng.core.localization.PlayerMessages;
import appeng.items.tools.powered.WirelessTerminalItem;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocator;
import appeng.menu.locator.MenuLocators;
import com.november.mcphone.feature.terminal.integration.TerminalIntegration;
import com.november.mcphone.feature.terminal.integration.TerminalSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 * Applied Energistics 2 的接入 —— 无线终端，以及 AE2WTLib 的各种终端。
 *
 * <h2>一个 instanceof 覆盖多少东西</h2>
 *
 * AE2 自己的无线终端、无线合成终端、无线样板访问终端，加上 AE2WTLib 的全部终端，都是
 * {@link WirelessTerminalItem} 的子类。所以认领只要一句 instanceof，不用去数有多少种，
 * 以后 AE2 加了新的也自动认得。
 *
 * 但"认得出"不等于"开得对"——AE2WTLib 那一支必须走它自己的入口，理由见
 * {@link Ae2wtlibSupport}。
 *
 * <h2>电量、范围、维度、增幅卡</h2>
 *
 * 一条都不在这里。全在 AE2 自己的打开方法里，它自己查、自己拒绝、自己给玩家发消息。
 * 唯一的例外是卡槽那条路上的前提检查，见 {@link #checkPreconditions}——那是把 AE2 自己
 * 那一段照抄了一遍，不是我们新发明的规则。
 *
 * <h2>1.20.1 的 AE2 与 1.21.1 的差在哪</h2>
 *
 * 15.x 上"东西在哪儿"这件事分成了<b>两套</b>，而 19.x 已经合成一套（{@code ItemMenuHostLocator}）：
 *
 * <pre>
 *   开菜单     MenuOpener.open(菜单类型, 玩家, MenuLocator)     ← 认得自定义的位置
 *   取物品     IMenuItem.getMenuHost(玩家, int 槽位, 物品, 坐标) ← 只认背包槽位号
 * </pre>
 *
 * 所以背包里那台走它自己的 {@code openFromInventory(player, 槽位)}，卡槽那台走
 * {@code MenuOpener.open} 加我们注册的 {@link TerminalSlotLocator}。两条路都不必上 AT、
 * 不必反射：{@code WirelessTerminalMenuHost} 的构造是公开的，而且它的槽位参数收的是
 * <b>{@code Integer}</b> 而非 int ——给 null 就是"不在背包的任何一格里"，
 * {@code ensureItemStillInSlot()} 第一句就放行。AE2 把那个字段做成可空的，本来就是为
 * 终端不在普通背包里这种情况准备的。
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

    /** AE2 自己在 {@code checkPreconditions} 里用的那个数，照抄 */
    private static final double POWER_TO_OPEN = 0.5;

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
        if (!(stack.getItem() instanceof WirelessTerminalItem terminal)) return false;

        // 背包那一种：AE2 自己就有入口，前提检查也归它。
        // 【记录模式的 switch 是 Java 21 的东西】，这一支编到 17，只能写成 instanceof
        if (source instanceof TerminalSource.InventorySlot inventorySlot) {
            int index = inventorySlot.index();

            // AE2WTLib 那条必须先试：它的终端也是 WirelessTerminalItem，走下面那条会开出
            // 一个错误的菜单类型（普通 ME 终端，没有合成格），而且不报错
            if (hasAe2wtlib() && Ae2wtlibSupport.isWirelessTerminal(stack)) {
                return Ae2wtlibSupport.open(player, stack, MenuLocators.forInventorySlot(index));
            }
            return terminal.openFromInventory(player, index);
        }

        // 卡槽那一种：位置换成我们注册的那个，其余照 AE2 的 openFromInventory 原样来一遍
        MenuLocator locator = new TerminalSlotLocator();

        if (hasAe2wtlib() && Ae2wtlibSupport.isWirelessTerminal(stack)) {
            return Ae2wtlibSupport.open(player, stack, locator);
        }

        if (!checkPreconditions(terminal, stack, player)) return false;
        return MenuOpener.open(terminal.getMenuType(), player, locator);
    }

    /**
     * 把 AE2 的 {@code WirelessTerminalItem.checkPreconditions} 照抄一遍。
     *
     * <b>为什么要抄</b>：那个方法是 {@code protected} 的，从外面调不着；而
     * {@code openFromInventory} 这个唯一会调它的公开入口只收背包槽位号，卡槽这条路走不了。
     * 跳过它是能开的——{@code MenuOpener.open} 不查任何前提——但代价是"没电的终端点开之后
     * 闪一下自己关掉"，而不是一句"设备没有电力"。
     *
     * 抄的是<b>字节码</b>，不是印象（AE2 15.0.0 与 15.4.10 两头都核过，三步一字不差）：
     *
     * <ol>
     *   <li>物品还是这一件吗</li>
     *   <li>{@code getLinkedGrid}——绑没绑定、绑的那个网络还在不在。<b>它自己会给玩家发
     *       消息</b>（DeviceNotLinked / LinkedNetworkNotFound），所以这里不补</li>
     *   <li>{@code hasPower(玩家, 0.5, 物品)}，不够就发 AE2 自己那条 DeviceNotPowered</li>
     * </ol>
     *
     * 三个方法都是 public，所以这一段既不上 AT 也不反射。要是哪天 AE2 改了这段逻辑，
     * 我们这份会与它分叉——分叉的表现是"卡槽里那台的拒绝理由和手上那台不一样"，
     * 属于看得见的那一类，不是静默出错。
     */
    private static boolean checkPreconditions(WirelessTerminalItem terminal, ItemStack stack,
                                              ServerPlayer player) {
        if (stack.isEmpty() || stack.getItem() != terminal) return false;

        if (terminal.getLinkedGrid(stack, player.level(), player) == null) return false;

        if (!terminal.hasPower(player, POWER_TO_OPEN, stack)) {
            player.displayClientMessage(PlayerMessages.DeviceNotPowered.text(), true);
            return false;
        }
        return true;
    }

    /**
     * AE2WTLib 在不在场。
     *
     * 每次现问，不缓存成字段：{@link com.november.mcphone.feature.terminal.integration.Terminals}
     * 是在 setup 阶段构造这个类的，那时 ModList 已经成型，问一次和问一百次是同一个答案，
     * 而缓存要多一个字段、多一处"什么时候填"的约定。
     */
    private static boolean hasAe2wtlib() {
        return ModList.get().isLoaded(AE2WTLIB_MODID);
    }

    @Override
    public void setup() {
        TerminalSlotLocator.register();
    }
}
