package com.november.mcphone.feature.reader.client.source;

import com.november.mcphone.MCphone;
import com.november.mcphone.platform.PlayerAccess;
import com.november.mcphone.platform.ResourceIds;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

/** EvilCraft opens its book as a server-backed menu tied to the player's item. */
final class OriginsOfDarkness implements ExternalBook {
    private static final String MOD_ID = "evilcraft";
    private static final ResourceLocation BOOK_ID = ResourceIds.create(MOD_ID, "origins_of_darkness");

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public ResourceLocation bookId() {
        return BOOK_ID;
    }

    @Override
    public ResourceLocation item() {
        return BOOK_ID;
    }

    @Override
    public Component title() {
        return ExternalBook.itemTitle(BOOK_ID, Component.translatable("item.evilcraft.origins_of_darkness"));
    }

    @Override
    public boolean isAvailable() {
        return ExternalBook.super.isAvailable() && BuiltInRegistries.ITEM.get(BOOK_ID) != Items.AIR;
    }

    @Override
    public boolean open() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) return false;

        var item = BuiltInRegistries.ITEM.get(BOOK_ID);
        if (item == Items.AIR) return false;
        for (InteractionHand hand : InteractionHand.values()) {
            if (!client.player.getItemInHand(hand).is(item)) continue;
            try {
                // Send the normal item-use request so EvilCraft binds the menu to the same hand.
                return client.gameMode.useItem(client.player, hand).consumesAction();
            } catch (RuntimeException | LinkageError failure) {
                MCphone.LOGGER.warn("[MCphone] 打开手册 {} 失败", BOOK_ID, failure);
                return false;
            }
        }
        PlayerAccess.message(client.player, Component.translatable("mcphone.reader.hold_book", title()), true);
        return false;
    }
}
