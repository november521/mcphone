package com.november.mcphone.feature.terminal.integration;

import java.util.function.Function;

/**
 * 那台终端<b>放在哪儿</b> —— 手机的卡槽里，还是背包的某一格。
 *
 * 为什么需要这么一个东西
 *
 * 三家存储模组都得回答同一个问题："这个菜单背后的物品，等会儿去哪儿找"。菜单开着的时候，
 * 耗电要写回那个 ItemStack、界面设置要写回那个 ItemStack，而且客户端重建菜单时还要再找
 * 一次。三家给出的答案形状各不相同：
 *
 *   AE2   {@code ItemMenuHostLocator}，自带四种实现，可注册第五种
 *   RS 2  {@code SlotReference}，同样是可注册的一套
 *   Tom's 没有这个概念，它的 {@code open} 直接吃 ItemStack
 *
 * 所以这一格自己说一遍"在哪儿"，再由每个联动把它翻译成对方认的形状。翻译代码关在各自的
 * 联动类里，{@link Terminals} 和 {@code TerminalOpener} 一个外部类型都不用认识。
 *
 * 为什么不是简单地传 int（-1 表示卡槽）
 *
 * 那种编码要求每个读它的人都记得 -1 是什么意思，而忘了判断的后果是把 -1 当槽位号传给对方
 * ——AE2 那边会去 {@code getItem(-1)}。密封接口让编译器替我们盯着：漏一种就编不过。
 *
 * 分派靠的是 {@link #map}，不是 switch
 *
 * 原先两处翻译都写成对这个接口做模式匹配的 switch，穷尽性由编译器保证。那个保证要留住，
 * 但 switch 里的类型模式是 Java 21 才转正的（JEP 441），1.20.1 那一支跑在 17 上编不过。
 * {@code map} 两头都占：加第三种实现就必须实现它，而它多出来的那个参数会让所有调用点
 * 一起编不过 —— 和 switch 一样躲不掉，且是 Java 8 就成立的写法。
 */
public sealed interface TerminalSource {

    /** 手机卡槽里那一格。它只有一格，所以不需要任何字段 */
    record PhoneSlot() implements TerminalSource {

        @Override
        public <R> R map(Function<PhoneSlot, R> onPhoneSlot,
                         Function<InventorySlot, R> onInventorySlot) {
            return onPhoneSlot.apply(this);
        }
    }

    /**
     * 玩家背包的第 {@code index} 格。
     *
     * 编号就是 {@code Inventory.getItem} 的编号，覆盖快捷栏、主背包、副手与盔甲位；
     * AE2 与 RS 的"背包某一格"用的也是同一套编号。
     */
    record InventorySlot(int index) implements TerminalSource {

        @Override
        public <R> R map(Function<PhoneSlot, R> onPhoneSlot,
                         Function<InventorySlot, R> onInventorySlot) {
            return onInventorySlot.apply(this);
        }
    }

    /**
     * 按种类分派 —— 这个接口的穷尽性保证就落在这里。
     *
     * 调用方给每一种一个翻译函数，返回什么由调用方定（AE2 要 locator、RS 要
     * SlotReference）。这样翻译代码仍然关在各自的联动类里，这个接口一个外部类型
     * 都不用认识。
     *
     * 加第三种实现时，它必须实现这个方法，而<b>手上只有两个函数，没有一个吃得下它</b>
     * —— 除了抛异常无路可走，也就写不出一个"编得过但静默出错"的实现。这一点比
     * {@link com.november.mcphone.core.PhoneLocation#writeTo} 那边强：那边的抽象方法
     * 是 void 的，空方法体编得过，逼得出"写"逼不出"写对"。
     */
    <R> R map(Function<PhoneSlot, R> onPhoneSlot, Function<InventorySlot, R> onInventorySlot);

    TerminalSource PHONE_SLOT = new PhoneSlot();

    static TerminalSource phoneSlot() {
        return PHONE_SLOT;
    }

    static TerminalSource inventorySlot(int index) {
        return new InventorySlot(index);
    }
}
