package com.november.mcphone.core.script.server.economy;

import com.november.mcphone.api.economy.Currency;
import com.november.mcphone.api.economy.ICurrencyProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务器上有哪几种钱（施工方案 §22.7、§22.8）。来自独立配置
 * {@code <世界目录>/serverconfig/mcphone-economy.json}（{@link EconomyConfig}；E28），
 * 由 {@link EconomyRuntime} 按配置顺序注册。
 *
 * <h2>App 不许写死货币 id</h2>
 *
 * 写死了换台服务器就不通。作者要用 {@code ctx.currency.default()} 或 {@code list()}。
 *
 * <p><b>没有默认货币时 {@link #defaultCurrency()} 返回 null，不抛</b> ——
 * App 该 {@code ctx.fail('UNAVAILABLE', …)}，不该崩（§22.8）。
 */
public final class CurrencyRegistry {

    /** 货币 id → 交出去的那一个（有网关时是包过的）。按声明顺序，{@code list()} 的顺序就是它。 */
    private final Map<String, ICurrencyProvider> providers = new LinkedHashMap<>();

    private String defaultId;

    private final CurrencyGateway gateway;

    /** 断言测试用：不经网关，直接交出 provider。 */
    public CurrencyRegistry() {
        this(null);
    }

    /**
     * 生产环境用：登记进来的 provider 一律包一层 {@link GatedCurrencyProvider} 再交出去 ——
     * 拿到注册表的人（{@code ctx.currency}）碰不到没包过的那一个。
     */
    public CurrencyRegistry(CurrencyGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * 注册一种。{@code isDefault} 只许有一个为真，后来的覆盖前面的并记一条警告。
     *
     * <p>同一种货币的第二个实例<b>直接拒</b>（勘误 E25），不是静默覆盖：先注册的那个还在别处被引用着，
     * 两个实例各拿各的 {@code synchronized} 写同一份权威数据，锁就什么都不保证了 ——
     * 实测两实例并发 1000 次转账丢了 11 单位。
     *
     * <p>唯一实例只是守恒的一半，另一半是实例自己把调用互斥：脚本在多条 worker 上求值，宿主不串行化。
     *
     * @return 注册成功了没有
     */
    public boolean register(ICurrencyProvider provider, boolean isDefault) {
        String id = provider.currency().id().toString();
        ICurrencyProvider existing = providers.get(id);
        if (existing != null && unwrap(existing) != unwrap(provider)) {
            com.november.mcphone.MCphone.LOGGER.error(
                    "[MCphone] 货币 {} 已经有一个提供者了，拒绝第二个 —— "
                            + "两个实例各拿各的锁写同一份账，守恒就不成立了", id);
            return false;
        }
        if (existing == null) {
            // 已经包过的也拆开重包：别的网关（比如 onMainThread 恒为真的那种）包过的等于没包，线程模型只认这一个
            providers.put(id, gateway == null ? provider : new GatedCurrencyProvider(unwrap(provider), gateway));
        }
        if (isDefault) {
            if (defaultId != null && !defaultId.equals(id)) {
                com.november.mcphone.MCphone.LOGGER.warn(
                        "[MCphone] 配了不止一个默认货币，{} 覆盖了 {}", id, defaultId);
            }
            defaultId = id;
        }
        return true;
    }

    private static ICurrencyProvider unwrap(ICurrencyProvider p) {
        while (p instanceof GatedCurrencyProvider g) p = g.inner();
        return p;
    }

    public ICurrencyProvider get(String currencyId) {
        return providers.get(currencyId);
    }

    /** <b>没有就是 null</b>，不抛（§22.8）。 */
    public String defaultCurrency() {
        return defaultId;
    }

    /** 全部货币的元数据，给 {@code ctx.currency.list()}。 */
    public List<Currency> list() {
        List<Currency> out = new ArrayList<>();
        for (ICurrencyProvider p : providers.values()) out.add(p.currency());
        return out;
    }

    public java.util.Set<String> ids() {
        return providers.keySet();
    }

    public int size() {
        return providers.size();
    }

    /** 服务器停止时清掉 —— 静态表会把上一个世界钉住。 */
    public void clear() {
        providers.clear();
        defaultId = null;
    }
}
