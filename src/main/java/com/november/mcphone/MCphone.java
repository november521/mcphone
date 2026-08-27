package com.november.mcphone;

import com.mojang.logging.LogUtils;
import com.november.mcphone.core.ModCapabilities;
import com.november.mcphone.core.ModCreativeTabs;
import com.november.mcphone.core.ModItems;
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
 * 一副能编过、能装进游戏、能从创造栏里拿到手机物品的骨架，外加一条已经跑通的
 * 网络链路（壁纸的设置与同步）。手机的界面、其余 App、大部分网络包、联动都还
 * 没有——不是漏了，是还没移植。要移植什么、每一样卡在哪儿，逐条写在
 * docs/PORTING.md 里。
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

    public MCphone(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();

        ModItems.ITEMS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);
        modBus.addListener(ModCapabilities::register);

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

        LOGGER.info("[MCphone] Forge 1.20.1 骨架已加载。界面与多数 App 尚未移植，见 docs/PORTING.md");
    }
}
