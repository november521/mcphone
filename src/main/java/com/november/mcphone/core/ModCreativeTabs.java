package com.november.mcphone.core;

import com.november.mcphone.MCphone;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** 创造模式物品栏。眼下只有手机一件，先占住位置 */
public final class ModCreativeTabs {

    private ModCreativeTabs() {}

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MCphone.MODID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.mcphone"))
                    .icon(() -> new ItemStack(ModItems.PHONE.get()))
                    .displayItems((params, output) -> output.accept(ModItems.PHONE.get()))
                    .build());
}
