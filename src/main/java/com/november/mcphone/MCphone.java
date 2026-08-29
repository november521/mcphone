package com.november.mcphone;

import com.mojang.logging.LogUtils;
import com.november.mcphone.core.ModCapabilities;
import com.november.mcphone.core.ModCreativeTabs;
import com.november.mcphone.core.ModItems;
import com.november.mcphone.core.ModSounds;
import com.november.mcphone.core.menu.ModMenus;
import com.november.mcphone.core.net.NetworkHandler;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * MCphone —— Forge 1.20.1 那一支的入口。
 *
 * 这个工程现在是什么
 *
 * 功能已经从 NeoForge 1.21.1 那一支全部移植过来：开机、主屏、聊天、记事本、
 * 音乐、相册、相机、应用商店、末影箱、设置、浏览器、传送石、任务书、阅读、
 * 时钟、天气。215 个源文件里搬了 213 个，剩下两个是被这一支的
 * PhonePlayerData 与 PhoneItemData 取代掉的，不会再出现。
 *
 * 联动七个：Curios、NetMusic、Patchouli、Waystones + Balm、MCEF、FTB Quests、
 * GuideME。
 * 逐条记在 docs/PORTING.md 里 —— 它们在 1.20.1 上多半是【另一套 API】，
 * 改 bug 时别照着 main 那边的调用去对。
 *
 * 与 NeoForge 1.21.1 那一支的关系
 *
 * 那一支是功能完整的正式版，这一支从它移植。两边【不共用代码】，理由写在仓库
 * 根目录的 README 里：1.20.1 与 1.21.1 之间隔着物品数据、网络层、玩家数据、
 * 加载器四处大改，抽 common 层只会把两边都写扭曲。
 *
 * 所以改 bug 要改两遍。移植时请连同那边的类注释一起搬——那些注释里记着的多半
 * 是"为什么不能那样写"，而那种知识在两个版本上通常一样成立。
 *
 * 入口形状与 NeoForge 那边不一样，别照抄
 *
 * 两边都是构造函数注入，但注入的东西不同：这边给的是
 * {@link FMLJavaModLoadingContext}，要再问它要事件总线；NeoForge 21.x 直接给
 * IEventBus。@Mod 注解也不是同一个包。
 *
 * 【别】写成 FMLJavaModLoadingContext.get()。那个静态方法在 Forge 47.4 上已经
 * 标了 forRemoval，编译会告警，将来会直接没有。网上绝大多数 1.20.1 教程还是
 * 老写法，照抄会把这个警告带进来。
 */
@Mod(MCphone.MODID)
public final class MCphone {

    public static final String MODID = "mcphone";

    public static final Logger LOGGER = LogUtils.getLogger();

    /** 本模组版本号，构造期填进来 */
    private static String version = "";

    public MCphone(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();

        ModItems.ITEMS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);
        ModMenus.MENUS.register(modBus);
        ModSounds.SOUND_EVENTS.register(modBus);
        modBus.addListener(ModCapabilities::register);

        // SERVER 而非 COMMON：必须由服主一份说了算。
        // 1.21.1 那边是 modContainer.registerConfig(...)，这边走 context 实例——
        // 静态的 ModLoadingContext.get() 在 47.4 上已标 forRemoval
        context.registerConfig(net.minecraftforge.fml.config.ModConfig.Type.SERVER,
                com.november.mcphone.core.ServerConfig.SPEC, "mcphone-server.toml");

        // 网络包的注册【必须在构造期完成】。SimpleChannel 是按注册顺序发放
        // 整数序号的，等到 FMLCommonSetupEvent 之类再注册，两端的注册时机
        // 只要有一处不同，序号就对不上——而那不会报错，只会解出乱码字段
        NetworkHandler.register();

        // 玩家数据的附加与重生拷贝走 Forge 总线，不是 mod 总线。
        // AttachCapabilitiesEvent 是泛型事件，得用 addGenericListener 并把
        // 实体类型交出去，否则监听器收不到
        MinecraftForge.EVENT_BUS.addGenericListener(Entity.class,
                ModCapabilities::onAttachCapabilities);
        MinecraftForge.EVENT_BUS.addListener(ModCapabilities::onPlayerClone);

        // 游戏总线，显式挂载：这两条漏了没有任何症状，只是下线玩家的表再也不缩小
        MinecraftForge.EVENT_BUS.addListener(
                com.november.mcphone.core.net.RequestThrottle::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(
                com.november.mcphone.feature.music.DiscService::onPlayerLoggedOut);

        // 放在自家注册之后：兼容模块可能要看我们已经注册了什么
        com.november.mcphone.compat.CompatModules.init(modBus);

        // 1.21.1 那边由构造函数注入的 ModContainer 直接给出；1.20.1 上
        // FMLJavaModLoadingContext 没有 getModInfo，得回头去 ModList 里查自己
        version = net.minecraftforge.fml.ModList.get()
                .getModContainerById(MODID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("");

        // 客户端那一半。【必须走 DistExecutor】，不能直接写 MCphoneClient.init(modBus)：
        // 那句会让专用服务端在加载本类时连带解析 MCphoneClient，而它引用着
        // Minecraft、Screen 这些客户端类型，当场 NoClassDefFoundError。
        // 收 Supplier 的 Supplier 正是为了把那次引用推迟到确认在客户端之后。
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> MCphoneClient.init(context));

        LOGGER.info("[MCphone] Forge 1.20.1 已加载 v{}，见 docs/PORTING.md", version);
    }

    /** 本模组版本号，如 "0.1.0"。模组构造前调用会得到空串。 */
    public static String getVersion() {
        return version;
    }
}
