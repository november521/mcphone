package com.november.mcphone.feature.reader.client.source;

import com.november.mcphone.MCphone;
import com.november.mcphone.platform.ModPresence;
import com.november.mcphone.platform.ResourceIds;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** The Modopedia edition of Blood Magic's guide. Patchouli editions are discovered by their own source. */
final class SanguineScientiem implements ExternalBook {
    private static final String MOD_ID = "bloodmagic";
    private static final ResourceLocation BOOK_ID = ResourceIds.create(MOD_ID, "guide");
    private static final String BOOK_REGISTRY = "net.favouriteless.modopedia.api.registries.common.BookRegistry";
    private static final String BOOK_OPEN_HANDLER = "net.favouriteless.modopedia.client.BookOpenHandler";

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
        return null;
    }

    @Override
    public Component title() {
        return Component.translatable("guide.bloodmagic.name");
    }

    @Override
    public boolean isAvailable() {
        if (!ExternalBook.super.isAvailable() || !ModPresence.isLoaded("modopedia")) return false;
        try {
            Class<?> registryType = Class.forName(BOOK_REGISTRY);
            Object registry = registryType.getMethod("get").invoke(null);
            return registryType.getMethod("getBook", ResourceLocation.class).invoke(registry, BOOK_ID) != null;
        } catch (ReflectiveOperationException | LinkageError failure) {
            return false;
        }
    }

    @Override
    public boolean open() {
        Minecraft client = Minecraft.getInstance();
        var previousScreen = client.screen;
        try {
            Class<?> handler = Class.forName(BOOK_OPEN_HANDLER);
            handler.getMethod("tryOpenBook", ResourceLocation.class).invoke(null, BOOK_ID);
            if (client.screen != previousScreen) return true;
            MCphone.LOGGER.warn("[MCphone] Modopedia 未打开手册 {}", BOOK_ID);
        } catch (ReflectiveOperationException | LinkageError failure) {
            MCphone.LOGGER.warn("[MCphone] 打开手册 {} 失败", BOOK_ID, failure);
        }
        return false;
    }
}
