package com.november.mcphone.feature.music.menu;

import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.feature.music.DiscService;
import com.november.mcphone.feature.music.DiscState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 把玩家的 {@link ModAttachments#DISC} 附件包装成只有一格的 {@link Container}，给 {@link DiscBayMenu} 用。
 * 不存任何东西，读写都直接落到附件，避免两份数据要同步。
 * 换碟必须先掐掉正在放的：停止包按那张唱片的音效 ID 发，得趁旧唱片还在时算。
 * 本类会被专用服务器加载，不许出现客户端类。
 */
public final class DiscBayContainer implements Container {

    public static final int SIZE = 1;

    private final ServerPlayer player;

    public DiscBayContainer(ServerPlayer player) {
        this.player = player;
    }

    private DiscState state() {
        return player.getData(ModAttachments.DISC.get());
    }

    /** 先掐声音再换碟：停止包要靠旧唱片的音效 ID */
    private void setDisc(ItemStack stack) {
        DiscService.stopPlayback(player);
        player.setData(ModAttachments.DISC.get(), state().withDisc(stack));
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        return !state().hasDisc();
    }

    /** 交出活的那一张，不是副本：原版格子会就地改这个栈 */
    @Override
    public ItemStack getItem(int slot) {
        return slot == 0 ? state().disc() : ItemStack.EMPTY;
    }

    /** 不理会 count：仓里只可能有一张 */
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

        // 只收一张，与 DiscService.insert 同一道保险：别的模组的唱片未必不叠
        setDisc(stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
    }

    /** 附件那边一写就生效，这里没有第二份数据要落盘 */
    @Override
    public void setChanged() {
    }

    /** 菜单自己还会查一道；这里只确认问的是同一个人 */
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
