package com.november.mcphone.feature.terminal.menu;

import com.november.mcphone.core.menu.ModMenus;
import com.november.mcphone.feature.terminal.integration.Terminals;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 手机的终端卡槽 —— 一格，加玩家背包。
 *
 * 它解决什么
 *
 * 「身上有好几台终端时，点开 App 到底开哪一台」。在它之前的答案是"背包顺序里第一台"，
 * 那是个玩家没法预测、也没法改的规则。现在的答案是"你自己装进去的那一台"。
 *
 * 装进去是可选的
 *
 * 卡槽空着时点 App 到的就是这一页，而这一页上的「打开终端」按钮照样会去背包里找——所以
 * 不想用这个机制的人是多按一下，不是没得用。这一条不是客气：终端一旦离开背包，各家自己的
 * 快捷键（AE2WTLib 的补货/磁铁/收纳、RS 的打开无线网格）就找不到它了。把卡槽做成加成而
 * 不是替代，这些东西才不会被我们顺手弄坏。
 *
 * 为什么是一个真的容器界面，而不是画在手机屏幕里
 *
 * 手机那块 120×176 的屏幕上没有格子这回事：{@code IPhonePage} 只给 render / mouseClicked /
 * keyPressed，而"把终端放进去"要的恰恰是拖拽、shift 搬运、双击整理这些原版行为。所以这里
 * 走原版的 {@code AbstractContainerMenu}，白拿那一整套。
 *
 * 为什么不复用 {@code PhoneContainerMenu}（末影箱那个）
 *
 * 它是个等大的普通容器，表达不了这一格的两条要求：只收开得了的终端（mayPlace），以及
 * 下面那个「打开终端」按钮。唱片仓当初也是同一个理由自己写了一份。三者共用的是外壳
 * ——{@code PhoneChassis}，见 {@code TerminalSlotScreen}。
 */
public class TerminalSlotMenu extends AbstractContainerMenu {

    /*
     * 版面尺寸与坐标【全部】放在这里，屏幕那边只读不写。
     *
     * 格子的 x/y 是相对 leftPos/topPos 的，两边必须用同一套基准；在屏幕里再抄一份，
     * 就是等着哪天改了一处、格子画到背板外面去。唱片仓那边（DiscBayMenu）是同一个做法。
     */

    /** 与原版箱子同宽。手机竖屏机身只有 120px，放不下 9 列格子（要 162px） */
    public static final int IMAGE_WIDTH = 176;

    /**
     * 比原版箱子高一点：卡槽下面要塞得下一行提示和一个按钮。纵向预算：
     *
     *     标题        6 … 15
     *     卡槽(含边) 17 … 35     SLOT_Y = 18
     *     提示文字   39 … 48     HINT_Y
     *     按钮       50 … 70     BUTTON_Y + BUTTON_H
     *     「物品栏」  72 … 81     INVENTORY_LABEL_Y，原版算式
     *     背包(含边) 83 … 137
     *     快捷栏     141 … 159
     */
    public static final int IMAGE_HEIGHT = 166;

    /** 单个格子的间距（原版标准：16px 物品 + 2px 边框） */
    public static final int SLOT_SIZE = 18;

    /** 「物品栏」那行字的 Y，与原版同一个算式，这样它和下面的格子对得上 */
    public static final int INVENTORY_LABEL_Y = IMAGE_HEIGHT - 94;

    /** 卡槽那一格，横向居中 */
    public static final int SLOT_X = 80;
    public static final int SLOT_Y = 18;

    /** 卡槽旁边那行说明的 Y */
    public static final int HINT_Y = SLOT_Y + 21;

    /** 「打开终端」按钮 */
    public static final int BUTTON_X = 8;
    public static final int BUTTON_Y = 50;
    public static final int BUTTON_W = 160;
    public static final int BUTTON_H = 20;

    private static final int COLUMNS = 9;

    private final TerminalSlotContainer terminal;

    /**
     * 客户端与服务端共用这一个构造。
     *
     * 不需要额外的开局数据：卡槽的内容由 attachment 自己同步过来（见 TerminalSlot），不必再
     * 随开菜单的包发一遍。
     */
    public TerminalSlotMenu(int containerId, Inventory playerInventory) {
        super(ModMenus.TERMINAL_SLOT.get(), containerId);

        Player player = playerInventory.player;
        this.terminal = new TerminalSlotContainer(player);

        addSlot(new Slot(terminal, 0, SLOT_X, SLOT_Y) {
            /**
             * 只收<b>能从手机上打开</b>的终端。
             *
             * 问的是 {@link Terminals#isOpenable}，不是"是不是终端"——两者会不一样：
             * Tom's 的基础无线终端是终端，但它没有"隔空打开"这回事（{@code canOpen} 恒为
             * false，{@code open} 的方法体是一句 return）。装得进去却点不开，比装不进去
             * 更难解释，所以它在这一步就被挡住。
             *
             * 认哪些牌子由 {@link Terminals} 现问现答，这里不认识任何一家存储模组——三家
             * 全是软前置，这个类要在一家都没装的情况下也能加载。
             */
            @Override
            public boolean mayPlace(ItemStack stack) {
                return Terminals.isOpenable(stack);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });

        // 玩家背包三行
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                addSlot(new Slot(playerInventory, col + row * COLUMNS + COLUMNS,
                        8 + col * SLOT_SIZE,
                        84 + row * SLOT_SIZE));
            }
        }
        // 快捷栏
        for (int col = 0; col < COLUMNS; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * SLOT_SIZE, 142));
        }
    }

    /** 手机里现在装着的那台终端。屏幕拿它决定「打开」按钮亮不亮 */
    public ItemStack getTerminal() {
        return terminal.getItem(0);
    }

    /**
     * shift 点击。
     *
     * 卡槽 → 背包，背包 → 卡槽。后者靠 {@code moveItemStackTo} 自己去问 mayPlace，所以
     * shift 一个非终端物品不会有任何反应，这是对的。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index == 0) {
            // 卡槽 → 玩家背包（含快捷栏）
            if (!moveItemStackTo(stack, 1, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // 玩家背包 → 卡槽
            if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    /**
     * 一直有效。
     *
     * 卡槽挂在玩家身上而不是世界里的某个方块，所以不存在"走远了要关掉"这回事。
     */
    @Override
    public boolean stillValid(Player player) {
        return terminal.stillValid(player);
    }
}
