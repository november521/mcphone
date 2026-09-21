package com.november.mcphone.core.script.server.economy;

import com.november.mcphone.api.cost.EmcWallets;
import com.november.mcphone.api.economy.Currency;
import com.november.mcphone.api.economy.ICurrencyProvider;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 四档 provider 的构造（S15d′，§22.7）：<b>纯函数"计划" + 构造两段</b>。
 *
 * <p>{@link #plan(List, boolean)} 不起服务器就能断言（docs 里跑）：
 * 哪些注册、哪些跳过、跳过原因键、默认货币取哪条。{@link #build} 只做构造。
 *
 * <h2>不挂空壳（E12/E20/E33）</h2>
 *
 * <ul>
 *   <li>{@code emc_legacy}：只有真 {@code IEmcWallet} 在场（{@link EmcWallets#get()} 不是
 *       {@link EmcWallets#NONE}）才注册；否则跳过 + {@link #SKIP_EMC_NO_WALLET}。</li>
 *   <li>{@code adapter}：本步<b>没有任何 {@code ExternalWallet} 实现</b>（目标模组未定），
 *       一律不注册 + {@link #SKIP_ADAPTER_NO_BRIDGE}；桥到货的卡在这里加一条构造。</li>
 *   <li>{@code builtin} / {@code scoreboard}：按配置注册；上限传
 *       {@link CurrencySpec#effectiveMax()}（scoreboard 压到 int，别绕过）。</li>
 * </ul>
 */
public final class EconomyProviders {

    /** 跳过原因键：没有真 EMC 钱包。 */
    public static final String SKIP_EMC_NO_WALLET = "mcphone.economy.skip.emc_no_wallet";

    /** 跳过原因键：adapter 没有实现（桥未到货）。 */
    public static final String SKIP_ADAPTER_NO_BRIDGE = "mcphone.economy.skip.adapter_no_bridge";

    /** 跳过原因键：目录里出现过、但这里不认的 provider（理论上进不来，防御性）。 */
    public static final String SKIP_UNKNOWN_PROVIDER = "mcphone.economy.skip.unknown_provider";

    /** 一条货币的注册计划。{@code skipReasonKey} 非空 = 不注册，日志照它打。 */
    public record Planned(CurrencySpec spec, boolean register, boolean isDefault, String skipReasonKey) {
    }

    /** 计划表 + 顺带收集的警告（多条 default 之类）。 */
    public record Plan(List<Planned> entries, List<String> warnings) {
        public Plan {
            entries = List.copyOf(entries);
            warnings = List.copyOf(warnings);
        }
    }

    private EconomyProviders() {
    }

    /** 本服有没有"真的能用"的 EMC 钱包。 */
    public static boolean hasEmcWallet() {
        return EmcWallets.get() != EmcWallets.NONE;
    }

    /**
     * 纯函数：按配置产出"注册哪些 / 跳过哪些 + 原因键"。
     *
     * <p>默认货币取<b>第一条</b> {@code default=true}（E28：不是后者覆盖前者）；其余降为 false，
     * 并各来一条 warning。一条都没有 = 没有默认货币（{@code defaultCurrency()} 回 null，§22.8）。
     */
    public static Plan plan(List<CurrencySpec> specs, boolean hasEmcWallet) {
        List<Planned> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean defaultTaken = false;
        for (CurrencySpec spec : specs) {
            // 先定"注册/跳过"，再定默认位 —— **被跳过的 default 不许吃掉默认位**（对抗 #4）：
            // 否则"第一条 emc_legacy default + 没有钱包"会让后面真正的默认被静默忽略，最后没有默认货币。
            boolean register;
            String skipReason;
            switch (spec.provider()) {
                case "builtin", "scoreboard" -> {
                    register = true;
                    skipReason = "";
                }
                case "emc_legacy" -> {
                    register = hasEmcWallet;
                    skipReason = hasEmcWallet ? "" : SKIP_EMC_NO_WALLET;
                }
                // adapter：桥未到货，一律不注册（哪怕将来有桥，也要先在 build 里加实现）
                case "adapter" -> {
                    register = false;
                    skipReason = SKIP_ADAPTER_NO_BRIDGE;
                }
                default -> {
                    register = false;
                    skipReason = SKIP_UNKNOWN_PROVIDER;
                }
            }
            boolean isDefault = false;
            if (spec.isDefault()) {
                if (!register) {
                    warnings.add("'" + spec.id() + "' 是 default=true，但这一档本次不注册（"
                            + skipReason + "）；默认货币顺延给下一条 default=true");
                } else if (defaultTaken) {
                    warnings.add("多条 default=true：'" + spec.id() + "' 的 default 已忽略（取第一条能注册的）");
                } else {
                    isDefault = true;
                    defaultTaken = true;
                }
            }
            out.add(new Planned(spec, register, isDefault, skipReason));
        }
        return new Plan(out, warnings);
    }

    /** 构造一个注册用的 provider。{@code Planned.register == false} 的不要调它。 */
    public static ICurrencyProvider build(Planned planned, EconomyData data,
                                          Supplier<MinecraftServer> server, TxnLog log) {
        Currency currency = planned.spec().toCurrency();
        return switch (planned.spec().provider()) {
            case "builtin" -> new BuiltinProvider(currency, data, data.escrow(), log,
                    System::currentTimeMillis, false, planned.spec().effectiveMax());
            case "scoreboard" -> new ScoreboardProvider(currency, server, data.escrow(), log,
                    System::currentTimeMillis, false, planned.spec().effectiveMax());
            case "emc_legacy" -> new LegacyWalletProvider(currency);
            default -> throw new IllegalStateException("这一档不能构造（计划里应已跳过）："
                    + planned.spec().provider());
        };
    }
}
