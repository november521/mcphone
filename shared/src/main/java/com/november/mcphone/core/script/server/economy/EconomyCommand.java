package com.november.mcphone.core.script.server.economy;

import com.mojang.brigadier.CommandDispatcher;
import com.november.mcphone.api.economy.ICurrencyProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Set;

/**
 * {@code /mcphone economy audit}（§22.10）：把每种货币的账对一遍，<b>不平的那一行要显眼</b>。
 *
 * <p>要 OP 3 级：余额是所有人的隐私，对账结果本身也会暴露服务器的经济规模。
 * <b>权限挂在 {@code economy} 上，不许挂在 {@code mcphone} 根上</b>：brigadier 合并同名节点时只留先注册那一个的
 * requirement —— 挂在根上，别的 {@code /mcphone} 子命令先注册就让普通玩家也能对账，后注册就让它们对普通玩家消失。
 */
public final class EconomyCommand {

    private EconomyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mcphone")
                .then(Commands.literal("economy")
                        .requires(src -> src.hasPermission(3))
                        .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("audit").executes(ctx -> audit(ctx.getSource())))));
    }

    /**
     * {@code /mcphone economy status}（S15d′，对抗 D′4）：本服注册了哪些货币、哪条是默认、
     * 配置面有什么要注意的。不改判定逻辑，只让"钱走哪条"可查。
     */
    private static int status(CommandSourceStack src) {
        EconomyRuntime rt = EconomyRuntime.current();
        if (rt == null) {
            src.sendFailure(Component.literal("[货币] 货币系统没在运行"));
            return 0;
        }
        CurrencyRegistry registry = rt.registry();
        if (registry == null || registry.size() == 0) {
            src.sendSuccess(() -> Component.literal("[货币] 本服还没有注册任何货币"), false);
        } else {
            String dflt = registry.defaultCurrency();
            src.sendSuccess(() -> Component.literal("[货币] 本服注册了 " + registry.size() + " 种，默认："
                    + (dflt == null ? "（无）" : dflt)), false);
            for (String id : registry.ids()) {
                ICurrencyProvider raw = unwrap(registry.get(id));
                long max = raw.maxBalance();
                src.sendSuccess(() -> Component.literal("  " + id + "  " + tierOf(raw)
                        + "  decimals=" + raw.currency().decimals()
                        + "  default=" + id.equals(dflt)
                        + "  max=" + (max == Long.MAX_VALUE ? "无上限" : String.valueOf(max))), false);
            }
        }
        if (registry != null && registry.defaultCurrency() == null) {
            src.sendSuccess(() -> Component.literal("[货币] ⚠ 没有默认货币：ctx.currency.default() 回 null")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        for (String note : rt.configNotes()) {
            src.sendSuccess(() -> Component.literal("[货币] 配置：" + note), false);
        }
        return 1;
    }

    private static int audit(CommandSourceStack src) {
        EconomyRuntime rt = EconomyRuntime.current();
        if (rt == null) {
            src.sendFailure(Component.literal("[对账] 货币系统没在运行"));
            return 0;
        }
        EconomyData data = rt.data();
        if (data.wholeLock() != null) {
            src.sendFailure(Component.literal("[对账] ⚠⚠ 货币存档整份锁住了，没法对账：" + data.wholeLock()));
            return 0;
        }
        Set<String> ids = data.currencyIds();
        if (ids.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[对账] 还没有任何货币的账"), false);
            return 1;
        }
        Set<String> locked = data.lockedCurrencies();
        CurrencyRegistry registry = rt.registry();
        int unbalanced = 0;
        for (String id : ids) {
            if (locked.contains(id)) {
                src.sendFailure(Component.literal("[对账] ⚠⚠ " + id + " 的存档读坏了、锁住了，跳过（细节见服务器日志）"));
                unbalanced++;
                continue;
            }
            // S15d′：按 provider 的档分派 —— 只有 builtin 档的余额在我们的存档里，能对；
            // 别的档（计分板/外部钱包）打"不可对账"，既不报平也不算进不平（卡约束 8）。
            ICurrencyProvider raw = unwrap(registry == null ? null : registry.get(id));
            if (raw == null) {
                src.sendSuccess(() -> Component.literal("[对账] " + id + "：没有注册的 provider，跳过"), false);
                continue;
            }
            if (!(raw instanceof BuiltinProvider)) {
                String tier = tierOf(raw);
                src.sendSuccess(() -> Component.literal("[对账] " + id + "：" + tier
                                + " 档的余额不在我们的存档里，不可对账")
                        .withStyle(ChatFormatting.YELLOW), false);
                continue;
            }
            EconomyAudit.Result r = EconomyAudit.run(id, data);
            if (!r.balanced()) unbalanced++;
            Component line = Component.literal(r.describe())
                    .withStyle(r.balanced() ? ChatFormatting.GREEN : ChatFormatting.RED);
            src.sendSuccess(() -> line, false);
        }
        return unbalanced == 0 ? 1 : 0;
    }

    /** 注册表交出来的是网关包装；对账要看里面那一档。 */
    private static ICurrencyProvider unwrap(ICurrencyProvider provider) {
        return provider instanceof GatedCurrencyProvider gated ? gated.inner() : provider;
    }

    private static String tierOf(ICurrencyProvider provider) {
        if (provider instanceof ScoreboardProvider) return "scoreboard";
        if (provider instanceof LegacyWalletProvider) return "emc_legacy";
        if (provider instanceof AdapterProvider) return "adapter";
        return provider.getClass().getSimpleName();
    }
}
