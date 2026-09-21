package com.november.mcphone.core.script.server.economy;

import com.november.mcphone.api.economy.Currency;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.Map;

/**
 * 货币配置里一条货币（施工方案 §22.7、§22.8）。来源：独立配置
 * {@code <世界目录>/serverconfig/mcphone-economy.json} 的 {@code currency} 数组
 * （{@link EconomyConfig}；E28：独立 JSON 取代旧的 ServerConfig/toml 方案）。
 *
 * <pre>
 * { "id": "server:coin", "name": "金币", "symbol": "¢",
 *   "decimals": 2, "provider": "scoreboard", "default": true, "max": 2000000000 }
 * </pre>
 *
 * <h2>为什么是纯函数</h2>
 *
 * 各加载器读配置的机制不一样，而「这七个字段怎么解释、哪些值不合法」在三个加载器上是同一件事。
 * 分开之后这一份能在 {@code docs/} 的断言测试里跑 —— 那边是裸 JavaExec，起不了服务器。
 * {@link EconomyConfig} 只负责"读文件 + 定位行号"，校验规则只有这里一份。
 */
public record CurrencySpec(String id, String name, String symbol, int decimals,
                           String provider, boolean isDefault, long max) {

    /** §22.7 认得的 provider 名。<b>不认识就拒，不许静默退回 builtin</b> —— 那会让服主以为配对了。 */
    public static final java.util.Set<String> KNOWN_PROVIDERS =
            java.util.Set.of("builtin", "scoreboard", "adapter", "emc_legacy");

    public CurrencySpec {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id 不能为空");
        if (ResourceLocation.tryParse(id) == null) {
            throw new IllegalArgumentException("id 不是合法的 ResourceLocation：" + id);
        }
        if (name == null) name = "";
        if (symbol == null) symbol = "";
        if (symbol.length() > Currency.MAX_SYMBOL) {
            throw new IllegalArgumentException("symbol 最长 " + Currency.MAX_SYMBOL + "，收到 " + symbol.length());
        }
        if (decimals < 0 || decimals > Currency.MAX_DECIMALS) {
            throw new IllegalArgumentException("decimals 要在 0.." + Currency.MAX_DECIMALS + "，收到 " + decimals);
        }
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider 不能为空");
        provider = provider.toLowerCase(Locale.ROOT);
        if (!KNOWN_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("不认识的 provider：" + provider
                    + "，认得的是 " + KNOWN_PROVIDERS);
        }
        if (max < 0) throw new IllegalArgumentException("max 不能为负，收到 " + max);
    }

    /**
     * 从一张 {@code 键 → 值} 的表读一条。缺省值照 §22.7：
     * {@code symbol=""}、{@code decimals=0}、{@code provider="builtin"}、
     * {@code default=false}、{@code max=0}（0 表示「用这一档自己的上限」）。
     */
    public static CurrencySpec from(Map<String, Object> table) {
        if (table == null) throw new IllegalArgumentException("这一条是空的");
        return new CurrencySpec(
                str(table, "id", null),
                str(table, "name", ""),
                str(table, "symbol", ""),
                (int) num(table, "decimals", 0),
                str(table, "provider", "builtin"),
                bool(table, "default", false),
                num(table, "max", 0));
    }

    /**
     * 这条配置最终允许的余额上限。
     *
     * <p><b>scoreboard 那一档压到 int</b>：计分板的分值是 32 位整数，服主写多大都装不下。
     * 悄悄用配置里那个更大的数会让「上限」这条不变量在第一次越界时才暴露，而那时数值已经回绕了。
     */
    public long effectiveMax() {
        if ("scoreboard".equals(provider)) return ScoreboardProvider.clampMax(max);
        return max <= 0 ? Long.MAX_VALUE : max;
    }

    /** 元数据。判定按 {@link Currency#id()}，这里的 name / symbol / decimals 只影响显示（E19）。 */
    public Currency toCurrency() {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return new Currency(rl,
                Component.literal(name == null || name.isEmpty() ? rl.getPath() : name),
                symbol, decimals, null);
    }

    private static String str(Map<String, Object> t, String k, String dflt) {
        Object v = t.get(k);
        if (v == null) {
            if (dflt == null) throw new IllegalArgumentException("缺少必填字段 " + k);
            return dflt;
        }
        return String.valueOf(v);
    }

    private static long num(Map<String, Object> t, String k, long dflt) {
        Object v = t.get(k);
        if (v == null) return dflt;
        if (v instanceof Number n) {
            long asLong = n.longValue();
            if ((n instanceof Double || n instanceof Float) && asLong != n.doubleValue()) {
                throw new IllegalArgumentException(k + " 要写整数（不要带小数），收到 " + v);
            }
            return asLong;
        }
        // 严格：数字字段只收 JSON 数字。带引号的 "2" 看着像数、读出来是文本 ——
        // 静默替服主做决定比报一条错危险（对抗 D′1：数字档严、布尔档松的不对称）。
        throw new IllegalArgumentException(k + " 要写整数（不要加引号），收到 " + v);
    }

    private static boolean bool(Map<String, Object> t, String k, boolean dflt) {
        Object v = t.get(k);
        if (v == null) return dflt;
        if (v instanceof Boolean b) return b;
        // "yes" / "1" / "TRUE " 一律不猜：Boolean.parseBoolean 会把它们静默吞成 false，
        // 服主以为配了默认货币、运行期却拿不到（对抗 D′1）。
        throw new IllegalArgumentException(k + " 要写 true/false（不要加引号），收到 " + v);
    }
}
