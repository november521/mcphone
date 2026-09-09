package com.november.mcphone.feature.terminal.integration.ae2;

import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.locator.MenuLocators;
import com.november.mcphone.feature.terminal.TerminalSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 告诉 AE2「那台终端在手机的卡槽里」。
 *
 * AE2 的 locator 是干什么的
 *
 * 它回答一个问题：<b>这个菜单背后的物品，去哪儿找</b>。AE2 自带四种——方块实体、线缆
 * 部件、背包某一格、Curios 某一格。Curios 那一种的存在恰好说明这套东西就是为"终端不在
 * 普通背包里"准备的，我们这一种是同一个性质。
 *
 * 为什么必须自己注册一种，而不能用 {@code MenuLocators.forStack}
 *
 * 那个方法造出来的 {@code StackItemLocator} <b>压根没被注册</b>（AE2 的静态初始化里只注册
 * 了上面那四种）。菜单一打开，locator 就要写进包里发给客户端，而写的那一步会去注册表里查
 * ——查不到直接抛 IllegalArgumentException。
 *
 * 注册顺序不用管：线上格式是<b>类的全名字符串</b>（{@code writeUtf(getClass().getName())}），
 * 不是序号。所以我们和 AE2 谁先注册都一样，也不会因为版本更新而错位。这一点和 RS 那边
 * 不同，RS 用的是 ResourceLocation（见 {@code TerminalSlotReferenceFactory}）。
 *
 * 这个 locator 没有字段
 *
 * "哪台终端"这件事由玩家自己决定——手机卡槽只有一格。所以写包时一个字节都不用写，读包时
 * 造一个新的就行。玩家从连接上下文来，伪造不了。
 */
public record TerminalSlotLocator() implements ItemMenuHostLocator {

    /** 往 AE2 的 locator 注册表里登记这一种。由 {@link Ae2Integration#setup()} 调，两端都调 */
    public static void register() {
        MenuLocators.register(
                TerminalSlotLocator.class,
                (locator, buf) -> { },          // 没有字段可写
                buf -> new TerminalSlotLocator()   // 也没有字段可读
        );
    }

    /**
     * 手机卡槽里那台终端。
     *
     * 服务端与客户端都会被调到（客户端那次来自 {@code MenuTypeBuilder.fromNetwork}），
     * 两边都能答得上来——attachment 是同步的，见 {@link TerminalSlot}。
     */
    @Override
    public ItemStack locateItem(Player player) {
        return TerminalSlot.get(player);
    }

    /**
     * 没有方块命中点。
     *
     * 这个值是给"右键某个方块顺手打开"那条路用的（比如对着方块用样板终端），我们这条路
     * 不经过任何方块。AE2 自己的 {@code InventoryItemLocator} 在这种情况下也给 null。
     */
    @Override
    public BlockHitResult hitResult() {
        return null;
    }

    /**
     * 不在背包的任何一格里。
     *
     * 默认实现返回 null，正是我们要的：AE2 拿这个值去锁住"菜单开着时别动那一格"，而我们
     * 那台终端根本不在背包里，没有一格需要锁。
     */
    @Override
    public Integer getPlayerInventorySlot() {
        return null;
    }
}
