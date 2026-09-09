package com.november.mcphone.feature.music.menu;

import com.november.mcphone.core.PhoneItem;
import com.november.mcphone.core.menu.ModMenus;
import com.november.mcphone.feature.music.DiscService;
import com.november.mcphone.feature.music.net.MusicNetworking;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 唱片仓的菜单：一个唱片格 ＋ 玩家背包，让玩家能直接把唱片拖进去（主手放入那条路照旧）。
 * 必须是原版 Menu：物品搬运要服务端权威，格子是真正的 {@link Slot} 才能白拿原版的 shift/拆半/拖拽/防作弊。
 * 坐标照抄原版漏斗（176×133），只把那一格挪到正中。
 */
public class DiscBayMenu extends AbstractContainerMenu {

    public static final int IMAGE_WIDTH = 176;

    public static final int IMAGE_HEIGHT = 133;

    /** 单个格子的间距（原版标准：16px 物品 + 2px 边框） */
    public static final int SLOT_SIZE = 18;

    public static final int INVENTORY_LABEL_Y = IMAGE_HEIGHT - 94;

    private static final int COLUMNS = 9;

    private static final int DISC_SLOT_X = 8 + 4 * SLOT_SIZE;
    private static final int DISC_SLOT_Y = 20;

    /** 客户端构造：收到开菜单包时调用，先用等大的占位容器，内容由原版容器同步包填入 */
    public DiscBayMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, new SimpleContainer(DiscBayContainer.SIZE));
    }

    /** 服务端构造：容器背后就是玩家那份 DiscState 附件 */
    public DiscBayMenu(int containerId, Inventory playerInventory, Container container) {
        super(ModMenus.DISC_BAY.get(), containerId);
        checkContainerSize(container, DiscBayContainer.SIZE);

        final RegistryAccess registries = playerInventory.player.level().registryAccess();

        addSlot(new Slot(container, 0, DISC_SLOT_X, DISC_SLOT_Y) {
            /** 只收放得响的唱片。判据整个委托给 {@link DiscService#isPlayableDisc}，与主手放入那条路必须是同一个 */
            @Override
            public boolean mayPlace(ItemStack stack) {
                return DiscService.isPlayableDisc(registries, stack);
            }

            /** 一格只放一张。唱片本来就不叠，别的模组的未必守这条规矩 */
            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                addSlot(new Slot(playerInventory, col + row * COLUMNS + COLUMNS,
                        8 + col * SLOT_SIZE,
                        51 + row * SLOT_SIZE));
            }
        }

        for (int col = 0; col < COLUMNS; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * SLOT_SIZE, 109));
        }
    }

    /**
     * shift 点击：唱片仓与背包之间对搬。从背包往仓里搬时 moveItemStackTo 会替我们问 mayPlace。
     * 往外搬必须搬副本：仓里交出的是活的那一张，moveItemStackTo 会就地清空它，
     * 随后 setDisc → stopPlayback 就认不出是哪张唱片，停止包发不出去。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index < DiscBayContainer.SIZE) {
            // 唱片仓 → 背包。reverse=true 从快捷栏那头开始填，与原版一致
            ItemStack moving = original.copy();
            if (!moveItemStackTo(moving, DiscBayContainer.SIZE, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
            // 1.21.1 那边 setByPlayer 收两个参数（新栈 + 旧栈，用来算耐久/音效），
            // 1.20.1 只收新栈
            slot.setByPlayer(moving);
            return original;
        }

        // 背包 → 唱片仓。就地改的是背包那一张；落仓走 setItem，那时旧唱片还在，停得掉
        if (!moveItemStackTo(stack, 0, DiscBayContainer.SIZE, false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    /** 手机不在身上就关掉。判据必须与开菜单那道校验同一个方法；光标上那一份要单独查，玩家可能正把手机拿起来挪位置 */
    @Override
    public boolean stillValid(Player player) {
        return PhoneItem.isPhone(getCarried()) || PhoneItem.isCarriedBy(player);
    }

    /** 关掉菜单时把唱片仓的最新样子推给客户端：菜单走原版容器同步，手机界面读的还是进菜单前的快照 */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player instanceof ServerPlayer serverPlayer) {
            MusicNetworking.sync(serverPlayer);
        }
    }
}
