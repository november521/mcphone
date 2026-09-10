package com.november.mcphone.core;

import com.november.mcphone.MCphone;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;

/**
 * 创造物品栏。注册即构造（Fabric 无 DeferredRegister）。
 *
 * 原版 CreativeModeTab.builder() 在 NeoForge 上有无参重载（他们补丁加的），
 * 原版没有 —— 显式给 Row.TOP 与列 0，行为一致。
 */
public final class ModCreativeTabs {

    private ModCreativeTabs() {}

    /** 由 MCphone.onInitialize 调用 */
    public static void register() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
                ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "mcphone_tab"),
                CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                        .title(Component.translatable("itemGroup.mcphone"))
                        .icon(() -> ModItems.PHONE.get().getDefaultInstance())
                        .displayItems((params, output) -> {
                            output.accept(ModItems.PHONE.get());
                            output.accept(ModItems.TABLET.get());
                        })
                        .build());
    }
}
