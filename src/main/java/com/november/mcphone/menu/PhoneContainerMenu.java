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
 * 手机里的容器菜单 —— 容器内容与玩家背包【同屏】显示。
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
 * 布局照抄原版箱子
 * ============================================================
 *
 * 格子坐标与原版 ChestMenu 的算式逐字一致：9 列，容器在上、
 * 玩家背包在下，中间留出标签行。这样做有两个好处：
 *   - 玩家的肌肉记忆完全对得上，拖拽、双击整理都在熟悉的位置
 *   - 不必自己推导坐标，少一处算错的机会
 *
 * 手机竖屏机身只有 120px 宽、放不下 9 列（需 162px），所以容器界面
 * 不沿用机身尺寸——为了迁就外壳而把两个区域拆成页签，会导致看不见
 * 对面、也没法拖拽，而"把东西放进末影箱"正是这个界面唯一的用途。
 */
public class PhoneContainerMenu extends AbstractContainerMenu {

    /** 每行格子数，与原版容器一致 */
    public static final int COLUMNS = 9;

    /** 单个格子的间距（原版标准：16px 物品 + 2px 边框） */
    public static final int SLOT_SIZE = 18;

    /** 界面宽度，与原版箱子一致 */
    public static final int IMAGE_WIDTH = 176;

    /** 玩家背包总格数：主背包 27 + 快捷栏 9 */
    private static final int PLAYER_INVENTORY_SIZE = 36;

    private final Container container;
    private final int containerSize;
    private final int containerRows;

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
        this.containerRows = containerSize / COLUMNS;
        container.startOpen(playerInventory.player);

        // 原版 ChestMenu 用这个偏移把玩家背包顶到容器下方，
        // 行数不是 4 时整体上移/下移。3 行箱子时为 -18。
        final int rowOffset = (containerRows - 4) * SLOT_SIZE;

        // ---- 容器内容：9 列 ----
        for (int row = 0; row < containerRows; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                addSlot(new Slot(container, col + row * COLUMNS,
                        8 + col * SLOT_SIZE,
                        18 + row * SLOT_SIZE));
            }
        }

        // ---- 玩家主背包（索引 9..35）----
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                addSlot(new Slot(playerInventory, col + row * COLUMNS + COLUMNS,
                        8 + col * SLOT_SIZE,
                        103 + row * SLOT_SIZE + rowOffset));
            }
        }

        // ---- 快捷栏（索引 0..8）----
        for (int col = 0; col < COLUMNS; col++) {
            addSlot(new Slot(playerInventory, col,
                    8 + col * SLOT_SIZE,
                    161 + rowOffset));
        }
    }

    /** 界面高度，算法同原版 ContainerScreen */
    public int getImageHeight() {
        return 114 + containerRows * SLOT_SIZE;
    }

    /** "物品栏"标签的 Y 坐标，同原版 */
    public int getInventoryLabelY() {
        return getImageHeight() - 94;
    }

    public int getContainerSize() {
        return containerSize;
    }

    /** shift 点击：在容器与玩家背包之间对搬 */
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
     * 判定必须与开箱那道校验用【同一个】方法。两处各写各的，就会出现
     * "服务端放你开、菜单自检又把你踢出去"——界面一闪而过，而且看不出
     * 是哪一步拒绝了。手机挂在饰品槽时就正是这样：开箱那边认饰品栏，
     * 这里原先不认，于是每次都开了又立刻关。
     *
     * 光标上那一份要单独查：玩家完全可能把手机本身拿起来挪位置，那一
     * 瞬间它既不在手上也不在任何格子里，而是在鼠标上。漏掉的话，菜单
     * 会在拖动途中被关掉，物品可能掉出来。
     */
    @Override
    public boolean stillValid(Player player) {
        return PhoneItem.isPhone(getCarried()) || PhoneItem.isCarriedBy(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        this.container.stopOpen(player);
    }
}
