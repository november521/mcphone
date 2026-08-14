package com.november.mcphone.api.cost;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

import java.util.function.Predicate;

/**
 * 一项代价 —— 让某个操作要求玩家先付出点什么。
 *
 * ================================================================
 * 【附属模组开发者指南】
 * ================================================================
 *
 * 手机里不少事情可以是有代价的：下载一个 App 要交一件物品、打印一份
 * 笔记要用掉一本空书、某个功能每次使用扣一点货币。这些场景的共同点
 * 只有三件事——够不够、扣掉它、告诉玩家要什么——所以抽成这一个接口。
 *
 * 现成的实现见 {@link ItemCost}，多数情况直接用工厂方法就够：
 *
 *   ICost.of(Items.ENDER_CHEST, 1)          一个末影箱
 *   ICost.of(Items.DIAMOND, 3)              三颗钻石
 *   ICost.FREE                              白送
 *
 * 想要自定义规则（按经验等级、按自定义货币的 NBT、按玩家统计数据），
 * 直接实现本接口即可，MCphone 不关心你怎么算。
 *
 * ================================================================
 * 三条约定，实现时务必遵守
 * ================================================================
 *
 * 一、{@link #consume} 只在【服务端】调用。客户端那边的物品栏只是一份
 *     副本，在那里扣东西既不会存档，还会和服务端对不上号。
 *
 * 二、{@link #consume} 必须先确认够了再动手，不能扣到一半发现不够。
 *     那会让玩家白白损失前半截物品，而且他根本不知道发生了什么。
 *
 * 三、{@link #canAfford} 不得有副作用。界面会每帧调它来决定按钮是亮是灰，
 *     在里面改任何状态都会变成"看一眼就被扣钱"。
 */
public interface ICost {

    /**
     * 玩家付得起吗。
     *
     * 每帧都可能被界面调用，务必保持廉价且无副作用。
     */
    boolean canAfford(Player player);

    /**
     * 真的扣掉。只在服务端调用。
     *
     * @return 扣成功才返回 true；不够则原样不动并返回 false
     */
    boolean consume(Player player);

    /** 界面上怎么告诉玩家这要花什么，例如"1 × 末影箱" */
    Component describe();

    // ================================================================
    //  现成的
    // ================================================================

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
     * 要多少 EMC。
     *
     * 真正扣钱的是当前接上的 {@link IEmcWallet}。**目前还没有任何实现**
     * ——没人接的时候这项代价永远付不起，界面会把按钮画灰并说明原因，
     * 不会崩也不会白送。接法见 {@link EmcWallets}。
     */
    static ICost emc(long amount) {
        return new EmcCost(amount);
    }

    /**
     * 自定规则的物品消耗 —— 物品种类之外还要看别的条件时用这个。
     *
     * 比如"空白的书与笔"：书与笔和写过字的是同一种物品，区别在数据组件，
     * 光靠物品种类分不出来。
     *
     * @param filter      什么样的物品堆算数
     * @param count       要几个
     * @param description 界面上怎么说明
     */
    static ICost matching(Predicate<ItemStack> filter, int count, Component description) {
        return new ItemCost(filter, count, description);
    }
}
