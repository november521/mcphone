package com.november.mcphone.menu;

import com.november.mcphone.PhoneItem;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 手机内的容器菜单 —— 在手机机身里摆格子。
 *
 * ============================================================
 * 为什么用原版 Menu 而不自己实现物品交换
 * ============================================================
 *
 * "背包与手机 GUI 交换物品"听起来像要自己写，其实恰恰相反：
 * 物品搬运必须服务端权威，自己实现等于重写 shift 点击、拆半、
 * 交换、拖拽分配这一整套，还要自己防作弊——那是刷物品 bug 的温床。
 *
 * 只要格子是真正的 {@link Slot}，上述行为原版全包，我们只需决定
 * "格子画在哪儿"。这个类做的就是后者。
 *
 * ============================================================
 * 6 列布局与页签的由来
 * ============================================================
 *
 * 手机屏幕内宽只有 120px（PhoneTheme.PHONE_WIDTH），而原版容器是
 * 9 列 ×18px = 162px，塞不进去。故改为 6 列（6×18 = 108px）。
 *
 * 末影箱 27 格＝5 行（90px），玩家背包 36 格＝6 行（108px），
 * 两组同时显示要 198px，超过可用高度 176px。因此做成页签，
 * 一次只显示一组。
 *
 * 关键：63 个 Slot 全都在菜单里，只是靠 {@link Slot#isActive()}
 * 控制显隐。原版的 AbstractContainerScreen 在渲染与命中判定时都会
 * 跳过 isActive()==false 的格子，而服务端的搬运逻辑根本不看它——
 * 所以 shift 点击照样能把物品送进当前看不见的那一组。
 */
public class PhoneContainerMenu extends AbstractContainerMenu {

    /** 每行格子数。6×18=108px，塞得进 120px 宽的手机屏幕 */
    public static final int COLUMNS = 6;

    /** 单个格子的间距（原版标准：16px 物品 + 2px 边框） */
    public static final int SLOT_SIZE = 18;

    /** 页签：容器内容（末影箱） */
    public static final int TAB_CONTENT = 0;

    /** 页签：玩家背包 */
    public static final int TAB_INVENTORY = 1;

    /**
     * 格子区左上角，相对于 leftPos/topPos（即手机外框左上角）。
     *
     * X: 边框 4 + (120-108)/2 = 10，让 6 列在屏幕内居中
     * Y: 边框 4 + 状态栏 10 + 页签栏 14 = 28
     *
     * 界面类要照这个坐标画背板，故定义在这里由两边共用，
     * 分开写两份迟早对不上。
     */
    public static final int SLOT_ORIGIN_X = 10;
    public static final int SLOT_ORIGIN_Y = 28;

    /** 玩家背包总格数：主背包 27 + 快捷栏 9 */
    private static final int PLAYER_INVENTORY_SIZE = 36;

    private final Container container;
    private final int containerSize;

    /**
     * 当前页签。纯客户端的显示状态，不同步：
     * 它只影响 isActive()，而 isActive() 只被客户端的渲染与命中判定使用，
     * 服务端的搬运逻辑不看它。服务端那份副本恒为默认值，无妨。
     */
    private int activeTab = TAB_CONTENT;

    /**
     * 客户端构造：收到服务端的开菜单包时调用。
     * 此时拿不到真实容器，先用等大的占位容器，内容随后由同步包填入——
     * 这是原版 ChestMenu 的既有做法。
     */
    public PhoneContainerMenu(MenuType<?> type, int containerId, Inventory playerInventory, int containerSize) {
        this(type, containerId, playerInventory, new SimpleContainer(containerSize), containerSize);
    }

    /** 服务端构造：容器是玩家真正的末影箱 */
    public PhoneContainerMenu(MenuType<?> type, int containerId, Inventory playerInventory,
                              Container container, int containerSize) {
        super(type, containerId);
        checkContainerSize(container, containerSize);

        this.container = container;
        this.containerSize = containerSize;
        container.startOpen(playerInventory.player);

        // ---- 页签 0：容器内容，6 列铺开 ----
        for (int i = 0; i < containerSize; i++) {
            addSlot(new TabSlot(container, i,
                    SLOT_ORIGIN_X + (i % COLUMNS) * SLOT_SIZE,
                    SLOT_ORIGIN_Y + (i / COLUMNS) * SLOT_SIZE,
                    TAB_CONTENT));
        }

        // ---- 页签 1：玩家背包，同样 6 列 ----
        // 索引顺序照原版：0-8 是快捷栏，9-35 是主背包。这里按 9..35 再 0..8
        // 排布，让主背包在上、快捷栏在下，与玩家的空间直觉一致。
        for (int i = 0; i < 27; i++) {
            addSlot(new TabSlot(playerInventory, 9 + i,
                    SLOT_ORIGIN_X + (i % COLUMNS) * SLOT_SIZE,
                    SLOT_ORIGIN_Y + (i / COLUMNS) * SLOT_SIZE,
                    TAB_INVENTORY));
        }
        for (int i = 0; i < 9; i++) {
            int row = 27 / COLUMNS + (i / COLUMNS);   // 接着主背包往下排
            addSlot(new TabSlot(playerInventory, i,
                    SLOT_ORIGIN_X + (i % COLUMNS) * SLOT_SIZE,
                    SLOT_ORIGIN_Y + (row + 1) * SLOT_SIZE,  // +1 空一行与主背包区分
                    TAB_INVENTORY));
        }
    }

    /** 容器格数，界面类算行数要用 */
    public int getContainerSize() {
        return containerSize;
    }

    public int getActiveTab() {
        return activeTab;
    }

    public void setActiveTab(int tab) {
        this.activeTab = tab;
    }

    /**
     * shift 点击：在容器与玩家背包之间对搬。
     *
     * 目标格子当前是否可见完全无关——服务端不看 isActive()，
     * 所以在末影箱页签下 shift 点击照样能把东西塞回背包。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index < containerSize) {
            // 容器 → 玩家背包。reverse=true 从快捷栏那头开始填，与原版箱子一致
            if (!moveItemStackTo(stack, containerSize, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 玩家背包 → 容器
            if (!moveItemStackTo(stack, 0, containerSize, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    /**
     * 菜单是否仍然有效 —— 手机不在身上了就关掉，这才配叫"便携末影箱"。
     *
     * 不能只查手上：玩家完全可能在背包页签里把手机本身拿起来挪位置，
     * 那一瞬间手机既不在手上、也不在背包格子里，而是在鼠标上。
     * 漏掉光标那一份的话，菜单会在拖动途中被关掉，物品可能掉出来。
     */
    @Override
    public boolean stillValid(Player player) {
        if (getCarried().getItem() instanceof PhoneItem) return true;

        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() instanceof PhoneItem) return true;
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.getItem() instanceof PhoneItem) return true;
        }
        return false;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        this.container.stopOpen(player);
    }

    /**
     * 带页签归属的格子 —— 不属于当前页签就"不存在"。
     *
     * 原版 AbstractContainerScreen 的渲染与 findSlot 都会跳过
     * isActive()==false 的格子，靠这一个方法就能实现页签切换，
     * 不必去改格子坐标（Slot 的 x/y 是 final 的，也改不了）。
     */
    private final class TabSlot extends Slot {
        private final int tab;

        TabSlot(Container container, int index, int x, int y, int tab) {
            super(container, index, x, y);
            this.tab = tab;
        }

        @Override
        public boolean isActive() {
            return activeTab == tab;
        }
    }
}
