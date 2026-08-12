package com.november.mcphone;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredItem;

@Mod(MCphone.MODID)
public class MCphone {

    public static final String MODID = "mcphone";
    public static final Logger LOGGER = LogUtils.getLogger();

    // ===== 物品注册 =====
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    // 手机物品 —— 右键打开手机界面
    public static final DeferredItem<PhoneItem> PHONE = ITEMS.registerItem("phone",
            props -> new PhoneItem(props.stacksTo(1).rarity(Rarity.RARE)));

    public MCphone(IEventBus modEventBus, ModContainer modContainer) {
        ITEMS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        com.november.mcphone.network.WallpaperData.ATTACHMENT_TYPES.register(modEventBus);
        modEventBus.addListener(com.november.mcphone.network.NetworkHandler::register);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        LOGGER.info("MCphone 模组加载完成 —— 手机已就绪");
    }
}
