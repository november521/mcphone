package com.november.mcphone.feature.terminal.integration.refinedstorage;

import com.november.mcphone.feature.terminal.integration.TerminalIntegration;
import com.november.mcphone.feature.terminal.integration.TerminalSource;
import com.november.mcphone.feature.terminal.TerminalSlot;
import com.refinedmods.refinedstorage.api.network.grid.IGridManager;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.network.grid.factory.PortableGridGridFactory;
import com.refinedmods.refinedstorage.inventory.player.PlayerSlot;
import com.refinedmods.refinedstorage.item.NetworkItem;
import com.refinedmods.refinedstorage.item.blockitem.PortableGridBlockItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * RS 1.12 只接受真实背包槽位，所以手机槽终端会借空背包格打开并在菜单关闭后归位。
 */
public final class RefinedStorageIntegration implements TerminalIntegration {

    public static final String MODID = "refinedstorage";

    /** 显示名。与 modid 一样是编译期常量，理由见 {@code Ae2Integration.NAME} */
    public static final String NAME = "Refined Storage";

    private static final Map<UUID, InTransit> IN_TRANSIT = new HashMap<>();

    private record InTransit(int inventorySlot, ItemStack stack, AbstractContainerMenu menu) {}

    @Override
    public String modId() {
        return MODID;
    }

    @Override
    public String displayName() {
        return NAME;
    }

    @Override
    public boolean claims(ItemStack stack) {
        return stack.getItem() instanceof NetworkItem
                || stack.getItem() instanceof PortableGridBlockItem;
    }

    @Override
    public void setup() {
        MinecraftForge.EVENT_BUS.addListener(RefinedStorageIntegration::onContainerClosed);
    }

    @Override
    public boolean open(ServerPlayer player, ItemStack stack, TerminalSource source) {
        if (source instanceof TerminalSource.PhoneSlot) {
            return openFromPhoneSlot(player, stack);
        }
        if (!(source instanceof TerminalSource.InventorySlot inventorySlot)) return false;

        return openFromInventory(player, stack, inventorySlot.index());
    }

    private static boolean openFromPhoneSlot(ServerPlayer player, ItemStack stack) {
        Inventory inventory = player.getInventory();
        int slot = firstEmptySlot(inventory);
        if (slot < 0) {
            player.displayClientMessage(
                    Component.translatable("mcphone.terminal.inventory_space_required"), false);
            return true;
        }

        AbstractContainerMenu before = player.containerMenu;
        TerminalSlot.set(player, ItemStack.EMPTY);
        inventory.setItem(slot, stack);

        boolean handled = openFromInventory(player, stack, slot);
        if (player.containerMenu == before) {
            restore(player, slot, stack);
        } else {
            IN_TRANSIT.put(player.getUUID(), new InTransit(slot, stack, player.containerMenu));
        }
        return handled;
    }

    private static int firstEmptySlot(Inventory inventory) {
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            if (inventory.getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    private static boolean openFromInventory(ServerPlayer player, ItemStack stack, int slotIndex) {
        PlayerSlot slot = new PlayerSlot(slotIndex);

        if (stack.getItem() instanceof NetworkItem networkItem) {
            networkItem.applyNetwork(
                    player.server,
                    stack,
                    network -> network.getNetworkItemManager().open(player, stack, slot),
                    player::sendSystemMessage);
            return true;
        }

        if (stack.getItem() instanceof PortableGridBlockItem) {
            IGridManager grids = API.instance().getGridManager();
            grids.openGrid(PortableGridGridFactory.ID, player, stack, slot);
            return true;
        }

        return false;
    }

    private static void onContainerClosed(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        InTransit transit = IN_TRANSIT.get(player.getUUID());
        if (transit == null || event.getContainer() != transit.menu()) return;

        IN_TRANSIT.remove(player.getUUID());
        restore(player, transit.inventorySlot(), transit.stack());
    }

    private static void restore(ServerPlayer player, int inventorySlot, ItemStack stack) {
        Inventory inventory = player.getInventory();
        if (!TerminalSlot.get(player).isEmpty()) return;
        if (inventory.getItem(inventorySlot) != stack) return;

        inventory.setItem(inventorySlot, ItemStack.EMPTY);
        TerminalSlot.set(player, stack);
    }
}
