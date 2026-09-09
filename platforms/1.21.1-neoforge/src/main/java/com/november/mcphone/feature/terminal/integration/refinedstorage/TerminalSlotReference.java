package com.november.mcphone.feature.terminal.integration.refinedstorage;

import com.november.mcphone.feature.terminal.TerminalSlot;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReferenceFactory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 告诉 Refined Storage「那台无线网格在哪儿」——手机卡槽里，或者背包的某一格。
 *
 * 和 AE2 那边是同一件事
 *
 * RS 的 {@code SlotReference} 与 AE2 的 {@code ItemMenuHostLocator} 解决同一个问题：菜单
 * 开着的时候，那个物品去哪儿找。耗电要写回它、界面设置要写回它，客户端重建菜单时还要再
 * 找一次。两边的形状不同，但纪律相同——必须注册，不然一序列化就出事。
 *
 * 为什么背包那一格也用我们自己的
 *
 * RS 自带的 {@code InventorySlotReference} 有构造函数，但那个构造函数是<b>包内可见</b>的，
 * 对外只开了一个 {@code of(Player, InteractionHand)}——只能表达"主手/副手里那个"。我们要
 * 表达的是任意一格（背包里第一台终端可能在任何位置），所以两种情况都用这一个实现，
 * {@code -1} 表示卡槽。
 *
 * 一个类型两种含义会不会太挤：不会，因为它们回答的是同一个问题的两种答案，而且
 * {@link #resolve} 与 {@link #isDisabledSlot} 都必须同时照顾两种情况——分成两个类反而要
 * 注册两次、写两份编解码。
 */
public record TerminalSlotReference(int inventorySlot) implements SlotReference {

    /** {@link #inventorySlot} 取这个值时表示"在手机卡槽里"，不是背包里的任何一格 */
    public static final int PHONE_SLOT = -1;

    public static TerminalSlotReference phoneSlot() {
        return new TerminalSlotReference(PHONE_SLOT);
    }

    /**
     * 把它找出来。服务端与客户端都会被调到，两边都答得上来——卡槽是同步的 attachment
     * （见 {@link TerminalSlot}），背包本来就两端都有。
     *
     * 空的时候给 {@link Optional#empty()}：RS 拿这个判断"东西还在不在"，菜单会自己关掉。
     */
    @Override
    public Optional<ItemStack> resolve(Player player) {
        ItemStack stack = inventorySlot == PHONE_SLOT
                ? TerminalSlot.get(player)
                : itemAt(player, inventorySlot);
        return stack.isEmpty() ? Optional.empty() : Optional.of(stack);
    }

    /** 越界一律当空的。槽位号是跟着包过来的，不信它 */
    private static ItemStack itemAt(Player player, int slot) {
        var inventory = player.getInventory();
        if (slot < 0 || slot >= inventory.getContainerSize()) return ItemStack.EMPTY;
        return inventory.getItem(slot);
    }

    /**
     * 界面开着的时候，锁住终端待着的那一格。
     *
     * RS 拿这个防止玩家把正在用的那台网格移走。卡槽那种情况永远返回 false：那台终端根本
     * 不在背包里，没有一格需要锁——{@code -1} 不会等于任何真实槽位号，这也是选这个值的
     * 原因之一。
     */
    @Override
    public boolean isDisabledSlot(int slot) {
        return slot == inventorySlot;
    }

    @Override
    public SlotReferenceFactory getFactory() {
        return TerminalSlotReferenceFactory.INSTANCE;
    }
}
