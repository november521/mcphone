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
import net.minecraft.world.item.JukeboxSong;

/**
 * 唱片仓的菜单 —— 一个唱片格 ＋ 玩家背包。
 *
 * ================================================================
 * 这个界面存在的唯一理由
 * ================================================================
 *
 * 唱片仓原本只认主手：{@link com.november.mcphone.feature.music.DiscService#insert}
 * 从 MAIN_HAND 取那张唱片。可手机界面里没有背包，玩家想放一张唱片进去就得
 * 关手机 → 把唱片翻到手上 → 再开手机 → 点那一条。唱片收在背包深处时更麻烦。
 *
 * 有了这一格，直接把唱片拖进去就行，主手那条路照旧留着（手上正好拿着时
 * 一步到位，不必开这个界面）。
 *
 * ================================================================
 * 为什么必须是原版 Menu
 * ================================================================
 *
 * 与末影箱那边同一条（见 {@link com.november.mcphone.core.menu.PhoneContainerMenu}）：
 * 物品搬运必须服务端权威。自己在界面上画几个格子、点一下发个包"把这个
 * 移到那儿"，等于重写 shift 点击、拆半、交换、拖拽分配这一整套，还要自己
 * 防作弊 —— 那是刷物品 bug 的温床。
 *
 * 只要格子是真正的 {@link Slot}，上述行为原版全包。
 *
 * ================================================================
 * 坐标照抄原版漏斗
 * ================================================================
 *
 * 漏斗也是"一行容器 + 玩家背包"，176×133，容器在 y=20、背包三行从 y=51
 * 起、快捷栏 y=109、"物品栏"那行字在 y=39。照抄的好处与末影箱那边一样：
 * 玩家的肌肉记忆对得上，也少一处自己推坐标算错的机会。
 *
 * 唯一的调整是把那一格挪到正中（第 5 列的位置），因为这里只有一格 ——
 * 孤零零挂在左上角会让人以为界面画歪了。
 */
public class DiscBayMenu extends AbstractContainerMenu {

    /** 界面宽度，与原版漏斗一致 */
    public static final int IMAGE_WIDTH = 176;

    /** 界面高度，与原版漏斗一致 */
    public static final int IMAGE_HEIGHT = 133;

    /** 单个格子的间距（原版标准：16px 物品 + 2px 边框） */
    public static final int SLOT_SIZE = 18;

    /** "物品栏"标签的 Y 坐标，同原版：高度 - 94 */
    public static final int INVENTORY_LABEL_Y = IMAGE_HEIGHT - 94;

    private static final int COLUMNS = 9;

    /** 唱片格摆在第 5 列的位置上，也就是正中 */
    private static final int DISC_SLOT_X = 8 + 4 * SLOT_SIZE;
    private static final int DISC_SLOT_Y = 20;

    /**
     * 客户端构造：收到服务端的开菜单包时调用。
     *
     * 此时拿不到真正的唱片仓，先用一个等大的占位容器，内容随后由原版的
     * 容器同步包填入 —— 与末影箱那边同一个做法。
     */
    public DiscBayMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, new SimpleContainer(DiscBayContainer.SIZE));
    }

    /** 服务端构造：容器背后就是玩家那份 DiscState 附件 */
    public DiscBayMenu(int containerId, Inventory playerInventory, Container container) {
        super(ModMenus.DISC_BAY.get(), containerId);
        checkContainerSize(container, DiscBayContainer.SIZE);

        // 两端都要判"这是不是一张唱片"，而注册表在两端都拿得到
        final RegistryAccess registries = playerInventory.player.level().registryAccess();

        // ---- 唱片格 ----
        addSlot(new Slot(container, 0, DISC_SLOT_X, DISC_SLOT_Y) {
            /**
             * 只收放得响的唱片。
             *
             * 判据整个委托给 {@link DiscService#isPlayableDisc} —— 主手放入
             * 那条路用的是同一个方法。两处各写各的会出现"拖得进去却按不响"
             * 或者反过来，而那种不一致玩家根本看不出原因。
             *
             * 认两种：原版唱片（查 JUKEBOX_PLAYABLE 组件，1.21 起这是数据
             * 驱动的，别的模组与数据包自定义的都认得），以及 NetMusic 刻好
             * 的 CD。空白 CD 不收 —— 放进去只会得到一个按了没反应的播放键。
             */
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

        // ---- 玩家主背包（索引 9..35）----
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                addSlot(new Slot(playerInventory, col + row * COLUMNS + COLUMNS,
                        8 + col * SLOT_SIZE,
                        51 + row * SLOT_SIZE));
            }
        }

        // ---- 快捷栏（索引 0..8）----
        for (int col = 0; col < COLUMNS; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * SLOT_SIZE, 109));
        }
    }

    /**
     * shift 点击：唱片仓与背包之间对搬。
     *
     * 从背包往仓里搬时，{@code moveItemStackTo} 会替我们问格子的 mayPlace，
     * 所以 shift 点一块石头不会把它塞进唱片仓 —— 不必在这里再判一次。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index < DiscBayContainer.SIZE) {
            // 唱片仓 → 背包。reverse=true 从快捷栏那头开始填，与原版一致
            if (!moveItemStackTo(stack, DiscBayContainer.SIZE, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 背包 → 唱片仓
            if (!moveItemStackTo(stack, 0, DiscBayContainer.SIZE, false)) {
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
     * 菜单是否仍然有效 —— 手机不在身上了就关掉。
     *
     * 与末影箱那边逐字相同的理由（见 PhoneContainerMenu.stillValid）：判定
     * 必须与开菜单那道校验用同一个方法，否则会出现"服务端放你开、菜单自检
     * 又把你踢出去"。光标上那一份要单独查，因为玩家完全可能把手机本身拿
     * 起来挪位置，那一瞬间它既不在手上也不在任何格子里。
     */
    @Override
    public boolean stillValid(Player player) {
        return PhoneItem.isPhone(getCarried()) || PhoneItem.isCarriedBy(player);
    }

    /**
     * 关掉菜单时把唱片仓的最新样子推给客户端。
     *
     * 玩家多半就是刚往里放了一张唱片，回到手机上那一条得立刻看见它。
     * 不推的话界面读的还是进这个菜单之前的快照。
     */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player instanceof ServerPlayer serverPlayer) {
            MusicNetworking.sync(serverPlayer);
        }
    }
}
