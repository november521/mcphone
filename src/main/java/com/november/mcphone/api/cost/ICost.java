package com.november.mcphone.api.cost;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

import java.util.function.Predicate;

/**
 * 一项代价 —— 让某个操作要求玩家先付出点什么：下载 App、打印笔记之类。
 *
 * 现成实现见 {@link ItemCost}，多数情况用 {@link #of}、{@link #FREE} 就够；
 * 想按经验等级或自定义货币算，直接实现本接口。
 *
 * 约定：{@link #consume} 只在服务端调用（客户端物品栏只是副本，扣了不存档还
 * 对不上号），且必须先确认够了再动手，不能扣到一半发现不够；{@link #canAfford}
 * 不得有副作用，界面每帧调它决定按钮亮灰。
 */
public interface ICost {

    /** 玩家付得起吗。每帧都可能被界面调用，务必保持廉价且无副作用 */
    boolean canAfford(Player player);

    /** 真的扣掉，只在服务端调用。扣成功才返回 true，不够则原样不动 */
    boolean consume(Player player);

    /** 界面上怎么告诉玩家这要花什么，例如"1 × 末影箱" */
    Component describe();

    /** 白送。占位用，省得调用方到处判 null */
    ICost FREE = new ICost() {
        @Override public boolean canAfford(Player player) { return true; }
        @Override public boolean consume(Player player) { return true; }
        @Override public Component describe() { return Component.empty(); }
    };

    /** 要几个某种物品 */
    static ICost of(ItemLike item, int count) {
        return new ItemCost(stack -> stack.is(item.asItem()), count,
                Component.translatable("mcphone.cost.item", count, item.asItem().getDescription()));
    }

    /**
     * 要多少 EMC，真正扣钱的是当前接上的 {@link IEmcWallet}。目前还没有任何实现，
     * 没人接时这项代价永远付不起，界面把按钮画灰并说明原因，不会崩也不会白送。
     */
    static ICost emc(long amount) {
        return new EmcCost(amount);
    }

    /**
     * 自定规则的物品消耗 —— 物品种类之外还要看别的条件时用，比如"空白的书与笔"：
     * 它和写过字的是同一种物品，区别在数据组件，光靠物品种类分不出来。
     */
    static ICost matching(Predicate<ItemStack> filter, int count, Component description) {
        return new ItemCost(filter, count, description);
    }
}
