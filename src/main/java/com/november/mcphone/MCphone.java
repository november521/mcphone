package com.november.mcphone;

import com.mojang.logging.LogUtils;
import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.core.ModCreativeTabs;
import com.november.mcphone.core.ModDataComponents;
import com.november.mcphone.core.PhoneItem;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(MCphone.MODID)
public class MCphone {

    public static final String MODID = "mcphone";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 运行时的真实版本号，tooltip 用它填 %s，语言文件里不写死。
     * 在构造函数里由 modContainer 赋值，不用 ModList 静态查询——那依赖 FML 的类加载时序，取不到时是静默的空值。
     */
    private static String version = "";

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredItem<PhoneItem> PHONE = ITEMS.registerItem("phone",
            props -> new PhoneItem(props.stacksTo(1).rarity(Rarity.RARE)));

    public MCphone(IEventBus modEventBus, ModContainer modContainer) {
        ITEMS.register(modEventBus);
        ModDataComponents.COMPONENTS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        com.november.mcphone.core.menu.ModMenus.MENUS.register(modEventBus);
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);
        com.november.mcphone.core.ModSounds.SOUND_EVENTS.register(modEventBus);
        modEventBus.addListener(com.november.mcphone.core.net.NetworkHandler::register);

        // 游戏总线，显式挂载：这两条漏了没有任何症状，只是下线玩家的表再也不缩小
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                com.november.mcphone.core.net.RequestThrottle::onPlayerLoggedOut);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                com.november.mcphone.feature.music.DiscService::onPlayerLoggedOut);

        // SERVER 而非 COMMON：必须由服主一份说了算，且 NeoForge 会同步给客户端供界面藏按钮
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,
                com.november.mcphone.core.ServerConfig.SPEC, "mcphone-server.toml");

        // 放在自家注册之后：兼容模块可能要看我们已经注册了什么
        com.november.mcphone.compat.CompatModules.init(modEventBus);

        version = modContainer.getModInfo().getVersion().toString();

        LOGGER.info("MCphone 模组加载完成 —— 手机已就绪");
    }

    /** 本模组版本号，如 "1.0.0"。模组构造前调用会得到空串。 */
    public static String getVersion() {
        return version;
    }
}
