package com.november.mcphone.feature.terminal.integration.refinedstorage;
import com.november.mcphone.feature.terminal.TerminalSlot;
import com.refinedmods.refinedstorage.common.api.support.slotreference.PlayerSlotReference;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
/** 手机卡槽与背包槽位的 RS 3 位置实现。槽位号仍按不可信网络输入检查。 */
public record TerminalSlotReference(int inventorySlot) implements PlayerSlotReference {
    public static final int PHONE_SLOT=-1;
    public static TerminalSlotReference phoneSlot() { return new TerminalSlotReference(PHONE_SLOT); }
    @Override public ItemStack get(Player player) {
        if(inventorySlot==PHONE_SLOT) return TerminalSlot.get(player);
        if(inventorySlot<0||inventorySlot>=player.getInventory().getContainerSize()) return ItemStack.EMPTY;
        return player.getInventory().getItem(inventorySlot);
    }
    @Override public void set(Player player,ItemStack stack) {
        if(inventorySlot==PHONE_SLOT) TerminalSlot.set(player,stack);
        else if(inventorySlot>=0&&inventorySlot<player.getInventory().getContainerSize()) player.getInventory().setItem(inventorySlot,stack);
    }
    @Override public boolean isDisabled(int slot) { return inventorySlot>=0&&slot==inventorySlot; }
    @Override public StreamCodec<RegistryFriendlyByteBuf,TerminalSlotReference> getStreamCodec() { return TerminalSlotReferenceFactory.CODEC; }
}
