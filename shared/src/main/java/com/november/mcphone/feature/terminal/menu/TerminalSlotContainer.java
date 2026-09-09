package com.november.mcphone.feature.terminal.menu;

import com.november.mcphone.feature.terminal.TerminalSlot;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 把手机卡槽包装成原版的 {@link Container}，好让它能当一个真正的 Slot 用。
 *
 * 为什么值得多这一层
 *
 * 因为拖拽、shift 搬运、双击整理、光标上跟随的物品、和创造模式物品栏的交互——只要格子是
 * 真正的 {@code Slot}，原版全包。自己撸一个"点一下就把背包里的终端塞进来"的假槽，上面
 * 每一条都得自己实现一遍，而且总有一条想不到。
 *
 * 它不持有任何东西
 *
 * 每个方法都是现读现写 {@link TerminalSlot} 那个 attachment，自己不存副本。存副本的话
 * 就会出现"菜单里看着有、attachment 里已经没了"这种两份状态对不上的经典毛病。
 */
final class TerminalSlotContainer implements Container {

    private final Player player;

    TerminalSlotContainer(Player player) {
        this.player = player;
    }

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return TerminalSlot.get(player).isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot == 0 ? TerminalSlot.get(player) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        if (slot != 0) return ItemStack.EMPTY;

        ItemStack stored = TerminalSlot.get(player);
        if (stored.isEmpty()) return ItemStack.EMPTY;

        ItemStack taken = stored.split(count);
        // split 是原地改的，attachment 那边不知道，得推一次同步
        TerminalSlot.set(player, stored.isEmpty() ? ItemStack.EMPTY : stored);
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot != 0) return ItemStack.EMPTY;

        ItemStack stored = TerminalSlot.get(player);
        TerminalSlot.set(player, ItemStack.EMPTY);
        return stored;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot == 0) TerminalSlot.set(player, stack);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public void setChanged() {
        TerminalSlot.markChanged(player);
    }

    /**
     * 只有这一个玩家用得了。
     *
     * 这个格子挂在玩家身上，不是世界里的一个方块，所以不存在"两个人同时开着它"的情况。
     */
    @Override
    public boolean stillValid(Player who) {
        return who == player;
    }

    @Override
    public void clearContent() {
        TerminalSlot.set(player, ItemStack.EMPTY);
    }
}
