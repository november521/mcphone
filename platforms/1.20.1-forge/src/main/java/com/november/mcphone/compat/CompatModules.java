package com.november.mcphone.compat;

import com.november.mcphone.MCphone;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.List;

/**
 * 兼容模块的登记表与统一装载入口。
 *
 * 加一个新的兼容模块要做什么
 *
 * 写一个类实现 {@link CompatModule}，再把它加进下面的 MODULES——就这两步。
 * 对方没装时自动跳过，不必在别处写任何"装没装"的判断。
 *
 * 为什么每个模块都要单独 try/catch
 *
 * 兼容模块干的事天然贴着模组加载流程，而 NeoForge 的注册阶段
 * （GameData.postRegisterEvents）只要收到【任何一个】异常，就会把整个
 * 注册表回滚成原版状态，然后崩溃退出。也就是说：一个写坏了的兼容模块，
 * 能让所有玩家的游戏起不来——而它本来只是个锦上添花的东西，这个代价
 * 完全不成比例。
 *
 * 所以这里把装载期的异常兜住，最坏的结果只是这一个兼容模块不生效。
 *
 * 但这里兜不住回调
 *
 * apply() 只负责把监听挂上去，真正的回调是之后由事件总线直接发起的，
 * 那时候栈上早就没有下面这个 try 了。所以各模块必须在自己的回调体内
 * 再兜一层，否则就会亲手制造上面说的那场崩溃。
 */
public final class CompatModules {

    private CompatModules() {}

    /** 全部兼容模块。加新的就往这里加一行 */
    private static final List<CompatModule> MODULES = List.of(
            new IntegratedDynamicsCompat(),
            new WaystonesWarpItemModule()
    );

    /**
     * 装载所有该装的兼容模块。在 MCphone 构造函数中调用一次。
     */
    public static void init(IEventBus modEventBus) {
        for (CompatModule module : MODULES) {
            try {
                if (!module.isNeeded()) continue;
                module.apply(modEventBus);
                MCphone.LOGGER.info("已启用兼容模块：{}", module.targetModId());
            } catch (Throwable t) {
                // 这里捕 Throwable 而不是 Exception 是刻意的：兼容模块最常见的
                // 翻车方式，是引用了对方模组里改了名或已删除的类，那抛出来的是
                // NoClassDefFoundError——属于 Error，不是 Exception，用后者接不住。
                MCphone.LOGGER.error("兼容模块 {} 装载失败，已跳过", module.targetModId(), t);
            }
        }
    }
}
