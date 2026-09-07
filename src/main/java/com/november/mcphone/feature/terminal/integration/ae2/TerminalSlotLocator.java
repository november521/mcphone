package com.november.mcphone.feature.terminal.integration.ae2;

import appeng.helpers.WirelessTerminalMenuHost;
import appeng.items.tools.powered.WirelessTerminalItem;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocator;
import appeng.menu.locator.MenuLocators;
import com.november.mcphone.feature.terminal.TerminalSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 * 告诉 AE2「那台终端在手机的卡槽里」。
 *
 * <h2>AE2 的 locator 是干什么的</h2>
 *
 * 它回答一个问题：<b>这个菜单背后的物品，去哪儿找</b>。15.x 自带四种——方块实体、线缆
 * 部件、背包某一格、手上那一只；AE2WTLib 自己另加了一种"Curios 的某个槽"
 * （{@code de.mari_023.ae2wtlib.curio.CurioLocator}）。那一种的存在恰好说明这套东西就是
 * 为"终端不在普通背包里"准备的，我们这一种是同一个性质，写法也是照着它来的。
 *
 * <h2>为什么必须自己注册一种</h2>
 *
 * 菜单一打开，locator 就要写进包里发给客户端（{@code MenuOpener} 干的），而写的那一步会
 * 去 {@link MenuLocators} 的注册表里查——查不到直接抛 IllegalArgumentException。
 *
 * 注册顺序不用管：线上格式是<b>类的全名字符串</b>，不是序号。所以我们和 AE2 谁先注册都
 * 一样，也不会因为版本更新而错位。
 *
 * <h2>这个 locator 没有字段</h2>
 *
 * "哪台终端"这件事由玩家自己决定——手机卡槽只有一格。所以写包时一个字节都不用写，读包时
 * 造一个新的就行。玩家从连接上下文来，伪造不了。
 *
 * <h2>1.21.1 那一支只写了三个覆盖，这边要自己拼 host</h2>
 *
 * 那边的 {@code ItemMenuHostLocator} 是个"给我物品就行"的接口（{@code locateItem}），
 * 由 AE2 负责把物品变成 menu host。15.x 的 {@link MenuLocator} 只有一个
 * {@code locate(玩家, 要什么类型)}，物品到 host 这一步得自己走完 —— 而通往它的公开入口
 * {@code IMenuItem.getMenuHost} 只收 {@code int} 槽位号，表达不了"不在背包里"。
 *
 * 绕法是直接 new 一个 {@code WirelessTerminalMenuHost}：它的构造是公开的，槽位那个参数收
 * 的是 {@code Integer} 而不是 int，<b>给 null 就是"不在背包的任何一格里"</b>
 * （{@code ensureItemStillInSlot()} 第一句就是 {@code if (slot == null) return true}）。
 * AE2 把它做成可空的本来就是为这种情况准备的。
 */
public record TerminalSlotLocator() implements MenuLocator {

    /** AE2WTLib 的 modid。与 {@link Ae2Integration} 里那个是同一个值，这里不能引用它的私有常量 */
    private static final String AE2WTLIB_MODID = "ae2wtlib";

    /** 往 AE2 的 locator 注册表里登记这一种。由 {@link Ae2Integration#setup()} 调，两端都调 */
    public static void register() {
        MenuLocators.register(
                TerminalSlotLocator.class,
                (locator, buf) -> { },             // 没有字段可写
                buf -> new TerminalSlotLocator()   // 也没有字段可读
        );
    }

    /**
     * 手机卡槽里那台终端的 menu host。
     *
     * 服务端与客户端都会被调到（客户端那次来自菜单在客户端的重建），两边都能答得上来——
     * 卡槽的内容是同步的，见 {@link TerminalSlot}。
     *
     * 答不上来时返回 null 而不是抛：AE2 与 AE2WTLib 两边拿到 null 都会安静地放弃开菜单，
     * 而这里能走到 null 的情形（玩家在菜单开着的时候把终端取走了）本来就该是放弃。
     */
    @Override
    public <T> T locate(Player player, Class<T> hostInterface) {
        Object host = menuHost(player);
        return hostInterface.isInstance(host) ? hostInterface.cast(host) : null;
    }

    private Object menuHost(Player player) {
        ItemStack stack = TerminalSlot.get(player);
        if (stack.isEmpty()) return null;

        // AE2WTLib 的终端得由它自己造 host：它的菜单类型取决于是哪一种终端，
        // 拿 AE2 的通用 host 顶上去会开出一个没有合成格的普通 ME 终端。见 Ae2wtlibSupport
        if (ModList.get().isLoaded(AE2WTLIB_MODID) && Ae2wtlibSupport.isWirelessTerminal(stack)) {
            return Ae2wtlibSupport.menuHost(player, this, stack);
        }

        if (!(stack.getItem() instanceof WirelessTerminalItem terminal)) return null;

        // 槽位给 null ＝ 不在背包的任何一格里，理由见类注释。
        // 最后那个参数是"从子菜单退回来时怎么再开一次"，照 AE2 自己那句来（它传的是
        // openFromInventory(player, 槽位, true)，第三个参数就是 MenuOpener.open 的
        // returningFromSubmenu）
        return new WirelessTerminalMenuHost(player, null, stack,
                (returning, subMenu) ->
                        MenuOpener.open(terminal.getMenuType(), returning, this, true));
    }
}
