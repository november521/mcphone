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
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredItem;

@Mod(MCphone.MODID)
public class MCphone {

    public static final String MODID = "mcphone";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 本模组的版本号，取自 neoforge.mods.toml（其值来自 gradle.properties 的 mod_version）。
     *
     * 存在的理由：物品 tooltip 要显示版本，而版本号一旦手写进语言文件，
     * 升版本时就得记得改中英两份，漏一份就是在骗玩家。这里取运行时的真值，
     * 语言文件只留 %s 占位。
     *
     * 在构造函数里赋值而非写成 static final 由 ModList 查询：
     * 后者要依赖 FML 的类加载时序，取不到时是静默的空值，排查成本高；
     * 而构造函数的 modContainer 由 FML 直接注入，必定有效。
     */
    private static String version = "";

    // ===== 物品注册 =====
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    // 手机物品 —— 右键打开手机界面
    public static final DeferredItem<PhoneItem> PHONE = ITEMS.registerItem("phone",
            props -> new PhoneItem(props.stacksTo(1).rarity(Rarity.RARE)));

    public MCphone(IEventBus modEventBus, ModContainer modContainer) {
        ITEMS.register(modEventBus);
        ModDataComponents.COMPONENTS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        com.november.mcphone.network.WallpaperData.ATTACHMENT_TYPES.register(modEventBus);
        modEventBus.addListener(com.november.mcphone.network.NetworkHandler::register);

        version = modContainer.getModInfo().getVersion().toString();

        LOGGER.info("MCphone 模组加载完成 —— 手机已就绪");
    }

    /** 本模组版本号，如 "1.0.0"。模组构造前调用会得到空串。 */
    public static String getVersion() {
        return version;
    }
}
