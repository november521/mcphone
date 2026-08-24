package com.november.mcphone.api.cost;

import com.november.mcphone.MCphone;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * EMC 钱包的挂载点 —— 全局一个，谁接上算谁的。
 *
 * 怎么接上一个真正的 EMC
 *
 * 在你模组的加载阶段调一次：
 *
 *   EmcWallets.set(new ProjectEWallet());
 *
 * 之后 MCphone 里所有以 EMC 计价的东西自动生效，不需要改 MCphone 任何
 * 代码，也不需要我们知道你的实现长什么样。
 *
 * 为什么是"一个"而不是像 App 那样注册一串
 *
 * 因为 EMC 是一种货币，不是一类插件。同时存在两个"EMC 余额"的世界里，
 * 玩家永远说不清自己到底有多少钱——该问哪个钱包、扣哪个、显示哪个，
 * 任何一种回答都会在某个场合出错。
 *
 * 所以这里只留一个位置。已经有人接上时再 set 会被拒绝并告警，而不是
 * 静默顶掉前一个——后者会让两个模组作者都查不出钱去哪了。
 *
 * 这个类两端都会加载
 *
 * 客户端拿它画余额与按钮状态，服务端拿它真扣。所以实现类里【不许】出现
 * 客户端类型，否则专用服务器会启动即崩。
 */
public final class EmcWallets {

    private EmcWallets() {}

    /**
     * 默认钱包：永远不可用。
     *
     * 存在的意义是让调用方不必判 null。没人接 EMC 时，以 EMC 计价的东西
     * 会显示成灰按钮加一句说明，而不是崩、也不是白送。
     */
    public static final IEmcWallet NONE = new IEmcWallet() {
        @Override public boolean isAvailable() { return false; }

        @Override public Component unavailableReason() {
            return Component.translatable("mcphone.emc.unavailable");
        }

        // 不可用时一律付不起。返回 true 会变成"没接 EMC 反而白送"，
        // 那是最糟的一种默认值
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

    /**
     * 接上一个钱包。
     *
     * @return true 表示接上了；已经有人接过时返回 false 并保留原来那个
     */
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
