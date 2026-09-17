package com.november.mcphone.core.script.server.economy;

import com.november.mcphone.api.economy.Currency;
import com.november.mcphone.api.economy.ICurrencyProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务器上有哪几种钱（施工方案 §22.7、§22.8）。来自 {@code mcphone-server.toml} 的
 * {@code [[economy.currency]]}。
 *
 * <h2>App 不许写死货币 id</h2>
 *
 * 写死了换台服务器就不通。作者要用 {@code ctx.currency.default()} 或 {@code list()}。
 *
 * <p><b>没有默认货币时 {@link #defaultCurrency()} 返回 null，不抛</b> ——
 * App 该 {@code ctx.fail('UNAVAILABLE', …)}，不该崩（§22.8）。
 */
public final class CurrencyRegistry {

    /** 货币 id → 提供者。按声明顺序，{@code list()} 的顺序就是它。 */
    private final Map<String, ICurrencyProvider> providers = new LinkedHashMap<>();

    private String defaultId;

    /**
     * 注册一种。{@code isDefault} 只许有一个为真，后来的覆盖前面的并记一条警告。
     *
     * <h2>一种货币只许有一个实例（勘误 E25）</h2>
     *
     * 守恒靠两条一起成立：宿主把调用串行化，<b>以及</b>同一种货币在这个进程里只有一个实例。
     * 少了后一条，两个实例各拿各的 {@code synchronized}、写同一份权威数据，
     * 锁就什么都不保证了 —— 实测两实例并发 1000 次转账丢了 11 单位。
     *
     * <p>所以重复注册<b>直接拒</b>，不是静默覆盖：覆盖的话先注册的那个实例还在别处被引用着，
     * 于是两个实例同时活着，正是上面那种情形。
     *
     * @return 注册成功了没有
     */
    public boolean register(ICurrencyProvider provider, boolean isDefault) {
        String id = provider.currency().id().toString();
        ICurrencyProvider existing = providers.get(id);
        if (existing != null && existing != provider) {
            com.november.mcphone.MCphone.LOGGER.error(
                    "[MCphone] 货币 {} 已经有一个提供者了，拒绝第二个 —— "
                            + "两个实例各拿各的锁写同一份账，守恒就不成立了", id);
            return false;
        }
        providers.put(id, provider);
        if (isDefault) {
            if (defaultId != null && !defaultId.equals(id)) {
                com.november.mcphone.MCphone.LOGGER.warn(
                        "[MCphone] 配了不止一个默认货币，{} 覆盖了 {}", id, defaultId);
            }
            defaultId = id;
        }
        return true;
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
