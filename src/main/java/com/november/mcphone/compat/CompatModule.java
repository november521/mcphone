package com.november.mcphone.compat;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

/**
 * 一个针对某个外部模组的兼容模块。
 *
 * ============================================================
 * 两类兼容，只有一类需要实现这个接口
 * ============================================================
 *
 * 能力型：对方在就用对方的功能，对方不在就退化成原样。典型是
 *   {@link CuriosCompat}——装了 Curios 就能把手机挂腰上，没装则一切照旧。
 *   这类是纯静态工具类，谁要用谁调，加载期不需要挂任何东西；让它实现本
 *   接口只会多出一个空的 apply()，所以它【不】在这里。
 *
 * 缺陷型：对方有 bug，我们主动绕开，让两边装在一起也能跑。这类必须在
 *   加载流程的特定时机插手，才需要本接口。装载入口见 {@link CompatModules}。
 *
 * 分这两类不是为了好看：能力型的代码是"被调用"的，出问题只影响那一个
 * 功能；缺陷型的代码是"自己抢在某个时机跑"的，出问题会波及整个加载流程。
 * 后者危险得多，所以单独立一套有兜底的装载机制管起来。
 */
public interface CompatModule {

    /** 这个模块针对哪个模组（modid）。同时用于装载判断与日志 */
    String targetModId();

    /**
     * 现在需不需要启用。
     *
     * 默认是"对方装了就启用"。碰上更刁钻的情况——比如某个 bug 只存在于
     * 对方的特定版本区间——可以覆盖本方法加上更细的判断，免得在已经修好
     * 的版本上还去动人家的东西。
     */
    default boolean isNeeded() {
        return ModList.get().isLoaded(targetModId());
    }

    /**
     * 装载：把自己需要的监听挂到模组总线上。
     *
     * 只在 {@link #isNeeded()} 为真时被调用。
     *
     * 这里抛异常不会拖垮模组，{@link CompatModules} 兜着；但【监听器回调里】
     * 抛的异常它兜不住，各模块必须自己在回调体内再兜一层——理由见
     * CompatModules 的类注释，那不是谨慎，是必须。
     */
    void apply(IEventBus modEventBus);
}
