package com.november.mcphone.feature.reader.client.source;

import com.november.mcphone.MCphone;
import com.november.mcphone.platform.ResourceIds;
import com.november.mcphone.platform.client.NativeExternalBookView;
import com.november.mcphone.platform.client.PhoneScreenBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Proxy;
import java.util.List;

/** Opens EvilCraft's original pages with a local item location dedicated to the reader. */
final class OriginsOfDarkness implements ExternalBook {
    private static final String MOD_ID = "evilcraft";
    private static final ResourceLocation BOOK = ResourceIds.create(MOD_ID, "origins_of_darkness");
    private static final ResourceLocation VIEW_LOCATION = ResourceIds.create(MCphone.MODID, "origins_reader");
    private static final String CYCLOPS_INVENTORY = "org.cyclops.cyclopscore.inventory.";
    private static final String EVILCRAFT = "org.cyclops.evilcraft.";

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public ResourceLocation bookId() {
        return BOOK;
    }

    @Override
    public ResourceLocation item() {
        return BOOK;
    }

    @Override
    public Component title() {
        return ExternalBook.itemTitle(BOOK, Component.translatable("item.evilcraft.origins_of_darkness"));
    }

    @Override
    public boolean isAvailable() {
        return ExternalBook.super.isAvailable() && BuiltInRegistries.ITEM.get(BOOK) != Items.AIR;
    }

    @Override
    public boolean open() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || !isAvailable()) return false;

        try {
            Screen manual = createManual(player.getInventory(), new ItemStack(BuiltInRegistries.ITEM.get(BOOK)));
            client.setScreen(new ReaderScreen(client.screen, player, manual));
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            MCphone.LOGGER.warn("[MCphone] 打开黑暗工艺的《黑暗起源》失败", failure);
            return false;
        }
    }

    private Screen createManual(Inventory inventory, ItemStack book) throws ReflectiveOperationException {
        Class<?> locationType = Class.forName(CYCLOPS_INVENTORY + "IInventoryLocation");
        Object inventoryLocation = Proxy.newProxyInstance(locationType.getClassLoader(),
                new Class<?>[] {locationType}, (proxy, method, args) -> switch (method.getName()) {
                    case "getItemInSlot" -> Integer.valueOf(0).equals(args[1]) ? book : ItemStack.EMPTY;
                    case "getInventory" -> null;
                    case "getUniqueName" -> VIEW_LOCATION;
                    case "setItemInSlot" -> throw new UnsupportedOperationException("Read-only guide");
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> VIEW_LOCATION.toString();
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        Class<?> itemLocation = Class.forName(CYCLOPS_INVENTORY + "ItemLocation");
        Object location = itemLocation.getConstructor(locationType, int.class).newInstance(inventoryLocation, 0);
        Class<?> menuType = Class.forName(EVILCRAFT + "inventory.container.ContainerOriginsOfDarkness");
        var menu = (AbstractContainerMenu) menuType.getConstructor(int.class, Inventory.class, itemLocation)
                .newInstance(0, inventory, location);
        if (!menu.slots.isEmpty()) throw new IllegalStateException("EvilCraft guide contains inventory slots");

        Class<?> screenType = Class.forName(EVILCRAFT + "client.gui.container.ContainerScreenOriginsOfDarkness");
        return (Screen) screenType.getConstructor(menuType, Inventory.class, Component.class)
                .newInstance(menu, inventory, title());
    }

    /** Hosts the original page widgets without installing its container on the connected player. */
    private static final class ReaderScreen extends PhoneScreenBase {
        private final Screen parent;
        private final LocalPlayer player;
        private final Screen manual;

        ReaderScreen(Screen parent, LocalPlayer player, Screen manual) {
            super(manual.getTitle());
            this.parent = parent;
            this.player = player;
            this.manual = manual;
        }

        @Override
        protected void init() {
            NativeExternalBookView.init(manual, Minecraft.getInstance(), width, height);
        }

        @Override
        protected boolean shouldDrawBackground() {
            return false;
        }

        @Override
        public List<? extends GuiEventListener> children() {
            // Cyclops Core rebuilds this list after turning a page.
            return manual.children();
        }

        @Override
        public void render(GuiGraphics graphics, int x, int y, float partialTick) {
            if (Minecraft.getInstance().player == player) {
                NativeExternalBookView.render(manual, graphics, x, y, partialTick);
            }
        }

        @Override
        protected boolean onScroll(double x, double y, double horizontal, double vertical) {
            return NativeExternalBookView.scroll(manual, x, y, horizontal, vertical);
        }

        @Override
        public void tick() {
            if (Minecraft.getInstance().player != player || !player.isAlive()) onClose();
        }

        @Override
        public void onClose() {
            Minecraft client = Minecraft.getInstance();
            client.setScreen(client.player == player && player.isAlive() ? parent : null);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
