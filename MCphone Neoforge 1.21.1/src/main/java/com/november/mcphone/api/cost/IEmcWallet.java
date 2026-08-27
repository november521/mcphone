package com.november.mcphone.api.cost;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * EMC 钱包 —— 把"玩家有多少 EMC、能不能扣"交给外部实现。
 *
 * 目前还没有真正的实现：默认的 {@link EmcWallets#NONE} 一律回答"不可用"，
 * 于是 {@link EmcCost} 的按钮永远是灰的并说明原因。接真实 EMC 只需写个实现
 * 类注册进 {@link EmcWallets}。
 *
 * 刻意不提供"读余额"的方法：ProjectE 的 EMC 早超出 long（用 BigInteger），
 * 接口写死 long 就得在"判断够不够"之前截断。比较与扣减都在实现内部完成。
 *
 * 约定：{@link #withdraw} 只在服务端调用，且必须先确认够了再动手；
 * {@link #canAfford} 与 {@link #describeBalance} 不得有副作用，界面每帧都调。
 */
public interface IEmcWallet {

    /** 返回 false 时界面把购买按钮画灰，并显示 {@link #unavailableReason()} */
    boolean isAvailable();

    /** 钱包不可用时，告诉玩家为什么。界面直接显示这句话 */
    Component unavailableReason();

    /** 付得起 amount 这么多 EMC 吗。每帧都可能被界面调用，务必廉价且无副作用 */
    boolean canAfford(Player player, long amount);

    /** 真的扣掉，只在服务端调用。扣成功才返回 true，不够则原样不动 */
    boolean withdraw(Player player, long amount);

    /** 玩家当前余额，显示用。返回 Component 而非数字，排版由实现者自己决定 */
    Component describeBalance(Player player);
}
