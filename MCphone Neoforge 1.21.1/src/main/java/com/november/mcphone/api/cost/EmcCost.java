package com.november.mcphone.api.cost;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * 以 EMC 计价的代价，真正扣钱的是当前接上的 {@link IEmcWallet}。没人接时钱包恒为
 * {@link EmcWallets#NONE}，于是按钮画灰、服务端也拦得住，不会白送。
 *
 * amount 用 long 而非 BigInteger：这是【价格】，由定价的人手写，不是余额；
 * 余额的表示方式留给钱包实现自己决定，本类从不碰它。
 */
public record EmcCost(long amount) implements ICost {

    @Override
    public boolean canAfford(Player player) {
        if (player.getAbilities().instabuild) return true;

        IEmcWallet wallet = EmcWallets.get();
        return wallet.isAvailable() && wallet.canAfford(player, amount);
    }

    @Override
    public boolean consume(Player player) {
        if (player.getAbilities().instabuild) return true;

        IEmcWallet wallet = EmcWallets.get();
        if (!wallet.isAvailable()) return false;

        // 不抢先调 canAfford：把"判"和"扣"拆开正是竞态的来源，由钱包自己一次做完
        return wallet.withdraw(player, amount);
    }

    @Override
    public Component describe() {
        IEmcWallet wallet = EmcWallets.get();
        if (!wallet.isAvailable()) {
            // "要多少"和"为什么用不了"要一起说，只说一半玩家就缺一半信息
            return Component.translatable("mcphone.cost.emc_unavailable",
                    amount, wallet.unavailableReason());
        }
        return Component.translatable("mcphone.cost.emc", amount);
    }
}
