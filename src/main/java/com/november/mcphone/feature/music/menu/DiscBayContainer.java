package com.november.mcphone.feature.music.menu;

import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.feature.music.DiscService;
import com.november.mcphone.feature.music.DiscState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 把唱片仓装成一个"只有一格的容器" —— 给 {@link DiscBayMenu} 用。
 *
 * 为什么不新开一份存储
 *
 * 唱片仓的真值一直存在玩家的 {@link ModAttachments#DISC} 附件里（跟着人走、
 * 死了不掉）。要是这里再存一份 SimpleContainer，就会有两份数据需要同步——
 * 而"两份数据同步"是刷物品与丢东西的经典来源。
 *
 * 所以这个类不存任何东西，它只是把附件包装成 {@link Container} 的形状：
 * 读就是读附件，写就是写附件。原版的取放、拖拽、shift 搬运照常工作，
 * 落点却始终是那一份真值。
 *
 * 换碟必须掐掉正在放的那一份
 *
 * 外放走的是原版音效，一旦发出去就会自己放到完 —— 服务端不盯着它
 * （见 {@link DiscState} 的类注释：在不在放是算出来的，没有 tick）。
 * 所以把唱片从仓里拿走的那一刻，得主动发一个停止包，否则玩家手里已经
 * 没有唱片了，音乐还在他身上响到底。
 *
 * 顺序不能反：停止包是按【那张唱片的音效 ID】发的，得趁旧唱片还在时算。
 *
 * 本类会被专用服务器加载，一个客户端类都不许出现
 *
 * 菜单在服务端建立，容器自然也在服务端。客户端那一侧拿到的是
 * {@link DiscBayMenu} 里那个等大的占位容器，内容由原版同步包填。
 */
public final class DiscBayContainer implements Container {

    /** 唱片仓只有一格 */
    public static final int SIZE = 1;

    private final ServerPlayer player;

    public DiscBayContainer(ServerPlayer player) {
        this.player = player;
    }

    private DiscState state() {
        return player.getData(ModAttachments.DISC.get());
    }

    /**
     * 换掉仓里那张唱片。
     *
     * 先掐声音再换碟，理由见类注释。{@link DiscState#withDisc} 顺带把
     * "开始外放的时刻"抹成 -1，于是状态与声音两边一致。
     */
    private void setDisc(ItemStack stack) {
        DiscService.stopPlayback(player);
        player.setData(ModAttachments.DISC.get(), state().withDisc(stack));
    }

    // ============================================================
    //  Container
    // ============================================================

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        return !state().hasDisc();
    }

    /**
     * 交出【活的】那一张，不是副本。
     *
     * 原版的格子会就地改这个栈（拆分、减数），拿到副本的话那些改动会
     * 落在空气上 —— SimpleContainer 也是交活的。
     */
    @Override
    public ItemStack getItem(int slot) {
        return slot == 0 ? state().disc() : ItemStack.EMPTY;
    }

    /**
     * 取走。
     *
     * 不理会 count：唱片本来就不叠，仓里也只可能有一张，"取走 n 个"
     * 与"取走那一张"是同一件事。
     */
    @Override
    public ItemStack removeItem(int slot, int count) {
        if (slot != 0 || count <= 0) return ItemStack.EMPTY;
        return removeItemNoUpdate(slot);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot != 0) return ItemStack.EMPTY;

        ItemStack taken = state().disc().copy();
        if (taken.isEmpty()) return ItemStack.EMPTY;

        setDisc(ItemStack.EMPTY);
        return taken;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot != 0) return;

        // 只收一张：唱片本来就不叠，但别的模组的唱片未必守这条规矩。
        // 与 DiscService.insert 那边同一道保险
        setDisc(stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
    }

    /** 附件那边一写就生效，这里没有第二份数据要落盘 */
    @Override
    public void setChanged() {
    }

    /**
     * 菜单自己还会查一道（见 {@link DiscBayMenu#stillValid}）。这里只确认
     * 问的是同一个人 —— 别人的唱片仓轮不到他动。
     */
    @Override
    public boolean stillValid(Player who) {
        return who == player;
    }

    @Override
    public void clearContent() {
        setDisc(ItemStack.EMPTY);
    }

    /** 一格只放一张。格子那边据此拒绝成摞的东西 */
    @Override
    public int getMaxStackSize() {
        return 1;
    }
}
