package com.november.mcphone;

import com.november.mcphone.gui.PhoneScreenRegistry;
import com.november.mcphone.gui.WallpaperStore;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = MCphone.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = MCphone.MODID, value = Dist.CLIENT)
public class MCphoneClient {

    public MCphoneClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // 扫描壁纸目录
        WallpaperStore.scan();

        // 触发 PhoneScreenRegistry 延迟加载（内建 + SPI）
        PhoneScreenRegistry.getAppCount();

        MCphone.LOGGER.info("MCphone 客户端加载完成");
        MCphone.LOGGER.info("玩家: {}", Minecraft.getInstance().getUser().getName());
    }
}
