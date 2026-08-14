package com.november.mcphone.api.cost;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * 以 EMC 计价的代价 —— {@link ICost} 的第二种实现。
 *
 * 用法与物品代价完全一样：
 *
 *   ICost.emc(2048)
 *
 * 真正扣钱的是当前接上的 {@link IEmcWallet}。没人接的时候（现在就是这样）
 * 钱包恒为 {@link EmcWallets#NONE}，于是：
 *
 *   canAfford → false      按钮画灰
 *   describe  → "2048 EMC（EMC 系统未接入）"
 *   consume   → false      服务端也拦得住，不会白送
 *
 * ================================================================
 * 为什么不等 EMC 真做出来再写这个类
 * ================================================================
 *
 * 因为它决定了 {@link ICost} 这个接口够不够用。物品代价是"数背包里有几个"，
 * EMC 代价是"问一个外部账本"——两者的形状差别足够大，现在就把第二种跑通，
 * 才能确认 canAfford/consume/describe 这三件套没有偏向物品。
 *
 * 结论是够用，一个方法都没加。将来接 ProjectE 时要写的只有钱包实现，
 * 这个类不必动。
 *
 * @param amount 要多少 EMC。用 long 而非 BigInteger：这是【价格】，由定价
 *               的人手写，不是余额。余额的表示方式留给钱包实现自己决定，
 *               本类从不碰它——理由见 {@link IEmcWallet} 的类注释
 */
public record EmcCost(long amount) implements ICost {

    @Override
    public boolean canAfford(Player player) {
        // 创造模式不花钱，与 ItemCost 一致
        if (player.getAbilities().instabuild) return true;

        IEmcWallet wallet = EmcWallets.get();
        return wallet.isAvailable() && wallet.canAfford(player, amount);
    }

    @Override
    public boolean consume(Player player) {
        if (player.getAbilities().instabuild) return true;

        IEmcWallet wallet = EmcWallets.get();
        if (!wallet.isAvailable()) return false;

        // 够不够由钱包自己先判再扣，这是 IEmcWallet 的第二条约定。
        // 这里不再抢先调一次 canAfford：两次查询之间余额可能已经变了，
        // 而把"判"和"扣"拆开正是竞态的来源
        return wallet.withdraw(player, amount);
    }

    @Override
    public Component describe() {
        IEmcWallet wallet = EmcWallets.get();
        if (!wallet.isAvailable()) {
            // 把"要多少"和"为什么用不了"一起说。只说其中一半，玩家要么
            // 不知道价格，要么不知道自己为什么买不了
            return Component.translatable("mcphone.cost.emc_unavailable",
                    amount, wallet.unavailableReason());
        }
        return Component.translatable("mcphone.cost.emc", amount);
    }
}
