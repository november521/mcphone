package com.november.mcphone.api.cost;

import com.november.mcphone.MCphone;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * EMC 钱包的挂载点 —— 全局只有一个位置，在加载阶段 {@code EmcWallets.set(...)} 一次。
 * 已经有人接上时再 set 会被拒绝并告警，不静默顶掉前一个。
 *
 * 两端都会加载（客户端画余额、服务端真扣），实现类里【不许】出现客户端类型，
 * 否则专用服务器启动即崩。
 */
public final class EmcWallets {

    private EmcWallets() {}

    /** 默认钱包：永远不可用。存在的意义是让调用方不必判 null */
    public static final IEmcWallet NONE = new IEmcWallet() {
        @Override public boolean isAvailable() { return false; }

        @Override public Component unavailableReason() {
            return Component.translatable("mcphone.emc.unavailable");
        }

        // 不可用时一律付不起，返回 true 会变成"没接 EMC 反而白送"
        @Override public boolean canAfford(Player player, long amount) { return false; }

        @Override public boolean withdraw(Player player, long amount) { return false; }

        @Override public Component describeBalance(Player player) {
            return Component.translatable("mcphone.emc.no_balance");
        }
    };

    private static IEmcWallet current = NONE;

    /** 当前钱包。永不为 null；没人接就是 {@link #NONE} */
    public static IEmcWallet get() {
        return current;
    }

    /** @return true 表示接上了；已经有人接过时返回 false 并保留原来那个 */
    public static boolean set(IEmcWallet wallet) {
        if (wallet == null) {
            MCphone.LOGGER.warn("[MCphone] EMC 钱包登记失败: 传入了 null");
            return false;
        }
        if (current != NONE) {
            MCphone.LOGGER.warn("[MCphone] EMC 钱包已由 {} 接管，忽略 {}",
                    current.getClass().getName(), wallet.getClass().getName());
            return false;
        }
        current = wallet;
        MCphone.LOGGER.info("[MCphone] EMC 钱包已接入: {}", wallet.getClass().getName());
        return true;
    }

    /** 现在有没有一个能用的 EMC 钱包 */
    public static boolean isAvailable() {
        return current.isAvailable();
    }
}
