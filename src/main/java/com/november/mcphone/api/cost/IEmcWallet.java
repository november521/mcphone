package com.november.mcphone.api.cost;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * EMC 钱包 —— 把"玩家有多少 EMC、能不能扣"这件事交给外部实现。
 *
 * ================================================================
 * 现状：这是一个留好的接口，还没有真正的实现
 * ================================================================
 *
 * MCphone 自己不产生 EMC，也不存 EMC。默认实现 {@link EmcWallets#NONE}
 * 一律回答"不可用"，于是 {@link EmcCost} 的按钮永远是灰的，并明确告诉
 * 玩家原因——而不是让他点下去没反应。
 *
 * 想接上真正的 EMC（比如 ProjectE 的等价交换），写一个实现类注册进
 * {@link EmcWallets} 即可，不必改 MCphone 的任何代码。
 *
 * ================================================================
 * 为什么不提供 getBalance() 这种"读余额"的方法
 * ================================================================
 *
 * 因为余额的表示方式不是我们能替实现者决定的。ProjectE 的 EMC 早就超出
 * long 的范围，用的是 BigInteger；自造的货币可能只是个 int。接口里一旦
 * 写死 long，接 ProjectE 时就得在边界上截断，而截断发生在"判断够不够"
 * 之前——玩家会看到一个明明够却买不了的价格。
 *
 * 所以这里只问结论，不问数字：够不够、扣掉它、这余额怎么显示给玩家。
 * 比较与扣减都在实现内部用它自己的类型完成，我们不掺和。
 *
 * ================================================================
 * 实现时的三条约定，与 {@link ICost} 一致
 * ================================================================
 *
 * 一、{@link #withdraw} 只在【服务端】调用。
 * 二、{@link #withdraw} 必须先确认够了再动手，不能扣到一半发现不够。
 * 三、{@link #canAfford} 与 {@link #describeBalance} 不得有副作用——界面
 *     每帧都可能调它们。
 */
public interface IEmcWallet {

    /**
     * 这个钱包现在能用吗。
     *
     * 典型的"不能用"：它对接的模组没装、玩家还没解锁 EMC 系统、
     * 服务端关掉了这个功能。返回 false 时界面会把购买按钮画灰，
     * 并显示 {@link #unavailableReason()}。
     */
    boolean isAvailable();

    /** 钱包不可用时，告诉玩家为什么。界面直接显示这句话 */
    Component unavailableReason();

    /**
     * 付得起 amount 这么多 EMC 吗。
     *
     * 每帧都可能被界面调用，务必廉价且无副作用。
     */
    boolean canAfford(Player player, long amount);

    /**
     * 真的扣掉。只在服务端调用。
     *
     * @return 扣成功才返回 true；不够则原样不动并返回 false
     */
    boolean withdraw(Player player, long amount);

    /**
     * 玩家当前余额，显示用。
     *
     * 返回 Component 而不是数字：既避开了表示方式的问题，也让实现者能自己
     * 决定怎么排版——"1.2M EMC"比"1200000"好读得多，而这该由懂这套货币的
     * 人来定。
     */
    Component describeBalance(Player player);
}
