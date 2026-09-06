package com.november.mcphone.core;

import com.november.mcphone.MCphone;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;

/** 创造模式页签注册。Fabric 侧直接用 {@link BuiltInRegistries#CREATIVE_MODE_TAB} 注册。 */
public class ModCreativeTabs {

    private ModCreativeTabs() {}

    public static void register() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
                ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "mcphone_tab"),
                CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                        .title(Component.translatable("itemGroup.mcphone"))
                        .icon(() -> MCphone.PHONE.getDefaultInstance())
                        .displayItems((params, output) -> {
                            output.accept(MCphone.PHONE);
                        })
                        .build());
    }
}
