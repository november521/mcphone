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
        com.november.mcphone.core.menu.ModMenus.MENUS.register(modEventBus);
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);
        com.november.mcphone.core.ModSounds.SOUND_EVENTS.register(modEventBus);
        modEventBus.addListener(com.november.mcphone.core.net.NetworkHandler::register);

        // 玩家下线时丢掉他的请求限流计时。挂在【游戏总线】上，显式添加而不
        // 依赖注解自动路由——漏了这一条不会有任何症状，限流照常工作，只是
        // 那张表再也不缩小了，半年后才看得出来
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                com.november.mcphone.core.net.RequestThrottle::onPlayerLoggedOut);

        // 服主的开关。用 SERVER 类型而不是 COMMON：它必须由服主一份说了算，
        // 而且 NeoForge 会把它同步给连上来的客户端，界面才能据此藏按钮
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,
                com.november.mcphone.core.ServerConfig.SPEC, "mcphone-server.toml");

        // 与外部模组的兼容处理。放在自家注册之后：兼容模块可能要看我们已经
        // 注册了什么，反过来则不成立。
        com.november.mcphone.compat.CompatModules.init(modEventBus);

        version = modContainer.getModInfo().getVersion().toString();

        LOGGER.info("MCphone 模组加载完成 —— 手机已就绪");
    }

    /** 本模组版本号，如 "1.0.0"。模组构造前调用会得到空串。 */
    public static String getVersion() {
        return version;
    }
}
