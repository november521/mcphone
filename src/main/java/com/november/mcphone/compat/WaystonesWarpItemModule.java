package com.november.mcphone.compat;

import com.november.mcphone.MCphone;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * 把「手机等于一块传送石」这条钩子挂到 Waystones 上。这一支特有，
 * NeoForge 那边没有对应文件。
 *
 * 为什么需要它
 *
 * 那一支开菜单时就能把 warpItem 一并交出去；Waystones 14.x 的菜单不收这个
 * 参数，只能等它自己去找 —— 而它只翻主手和副手。手机可以躺在背包里、也可以
 * 挂在饰品栏里，所以必须在传送真正发生前补上这一件。完整理由见
 * {@link WaystonesCompat} 的类注释。
 *
 * 为什么是 CompatModule 而不是在 WaystonesCompat 里自己挂
 *
 * {@link CompatModule} 的类注释把兼容分成两类：能力型（被调用的）与
 * 缺陷型（自己抢在某个时机跑的）。这条钩子属于后者 —— 它要在加载流程的
 * 特定时机插手，出问题会波及整个加载流程，所以走 {@link CompatModules}
 * 那套有兜底的装载机制。
 *
 * WaystonesCompat 本身仍是能力型的纯静态工具类，与那一支逐条对应，
 * 不实现本接口。
 *
 * 为什么挂在 FMLCommonSetupEvent 上，而不是构造期直接注册
 *
 * Balm 的事件表挂在它 runtime 实例上，理论上什么时候登记都行。但模组的
 * 构造顺序不由我们说了算，而 setup 阶段是【所有模组构造完之后】才开始的
 * —— 挑一个不需要推理就成立的时机，比省下这一次事件派发划算。
 *
 * 【必须 enqueueWork】：FMLCommonSetupEvent 是 ParallelDispatchEvent，会在
 * 多个 worker 上并行派发，而 Balm 那张事件表是普通的 Guava Table，不是
 * 线程安全的。这与 ModMenus 那边"手调 MenuScreens.register 必须包在
 * enqueueWork 里"是同一件事。
 */
public final class WaystonesWarpItemModule implements CompatModule {

    @Override
    public String targetModId() {
        // 与 App 声明联动用的是同一个来源，见 WaystonesCompat 里那个常量
        return WaystonesCompat.WAYSTONES_MODID;
    }

    @Override
    public void apply(IEventBus modEventBus) {
        modEventBus.addListener(this::onCommonSetup);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // 回调体内自己兜一层。CompatModules 的 try 只兜得住上面那句
            // addListener，兜不住这里 —— 而这里放跑一个异常，FML 会把它
            // 当成加载失败，整局游戏起不来。见 CompatModules 的类注释
            try {
                WaystonesCompat.registerWarpItemHook();
            } catch (Throwable t) {
                MCphone.LOGGER.error(
                        "挂传送石来源物品钩子失败，「传送石」App 将无法传送"
                                + "（Waystones 版本可能不兼容）", t);
            }
        });
    }
}
