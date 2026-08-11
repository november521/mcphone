package com.november.mcphone;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

public class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MCphone.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MCPHONE_TAB =
            TABS.register("mcphone_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.mcphone"))
                    .icon(() -> MCphone.PHONE.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(MCphone.PHONE.get());
                    })
                    .build());
}
