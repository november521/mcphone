package com.november.mcphone.core.script.server.economy;

import com.november.mcphone.api.economy.Balances;
import com.november.mcphone.api.economy.Currency;
import com.november.mcphone.api.economy.EscrowId;
import com.november.mcphone.api.economy.HoldResult;
import com.november.mcphone.api.economy.ICurrencyProvider;
import com.november.mcphone.api.economy.TxnReason;
import com.november.mcphone.api.economy.TxnResult;

import java.util.UUID;

/**
 * 把旧的 {@code api/cost} 钱包降级成一个货币提供者（施工方案 §22.2、§22.7 的 emc_legacy 一行）。
 *
 * <p><b>类名里不带旧单位名</b>：§22.12 有一条判据要求脚本层不出现那个旧单位名
 * （见 docs/script-app-s15-verification.md 的收口一节）——
 * 旧接口把单位名写进了类型，那正是 §22.2 批评它的第一条，新代码不该跟着犯。
 * 服主在 {@code mcphone-server.toml} 里写的 provider 名仍然是 {@code emc_legacy}（§22.7 的那一行）。
 *
 * <h2>为什么保留它</h2>
 *
 * {@code MCphoneApi} 的契约是只增不减，所以那套旧接口删不掉（§22.2）。
 * 但它撑不住新的货币 SDK：<b>没有存入、没有转账、没有托管</b>，只有"够不够"与"扣掉"。
 *
 * <h2>能力缺失是明写的，不是悄悄降级</h2>
 *
 * <ul>
 *   <li>{@link #hold} 一律 {@link TxnResult#UNAVAILABLE} —— <b>所以它不能用于市场类 App</b>：
 *       市场要"买家付款先押着、发货再放款"，没有托管就只能裸转，卖家跑单就没法退</li>
 *   <li>{@link #mint} / {@link #transfer} 同样不行：旧接口没有存入这一侧，
 *       只能扣不能加，转账是"一端扣一端加"，做不到</li>
 *   <li>{@link #balance} 返回 0 并不代表没钱 —— 旧接口<b>刻意不提供读余额</b>
 *       （ProjectE 的 EMC 用 BigInteger，写死 long 就得截断）</li>
 * </ul>
 *
 * 这些都要写进 {@link #unavailableReasonKey()} 与文档，让服主选它之前就知道。
 */
public final class LegacyWalletProvider implements ICurrencyProvider {

    /** 选了它但想干托管/转账时给的本地化键。 */
    public static final String KEY_NO_ESCROW = "mcphone.economy.emc_legacy.no_escrow";

    private final Currency currency;

    public LegacyWalletProvider(Currency currency) {
        this.currency = currency;
    }

    @Override
    public Currency currency() {
        return currency;
    }

    /** 旧钱包在不在场由 {@code api.cost} 那边说了算；这里只表达"这个提供者能力不全"。 */
    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String unavailableReasonKey() {
        return KEY_NO_ESCROW;
    }

    /**
     * 旧接口<b>刻意不提供读余额</b>（§22.2：ProjectE 的 EMC 用 BigInteger，写死 long 就得截断）。
     * 返回 0 不是"没钱"，是"读不到"。
     */
    @Override
    public long balance(UUID player) {
        return 0;
    }

    @Override
    public boolean allowNegative() {
        return false;
    }

    @Override
    public long maxBalance() {
        return Long.MAX_VALUE;
    }

    /**
     * 旧接口没有存入这一侧，转账做不到。
     *
     * <p>两端的判定仍然先走一遍（E25）：那一条说的是<b>参数合不合法</b>，与这一档
     * 做不做得到无关。四种 provider 对同一个非法调用要给同一个码，
     * 否则调用方还得按 provider 分情况记住谁给 INVALID、谁给 UNAVAILABLE。
     */
    @Override
    public TxnResult transfer(UUID from, UUID to, long amount, TxnReason reason) {
        TxnResult parties = Balances.checkParties(from, to);
        if (parties != TxnResult.OK) return parties;
        return TxnResult.UNAVAILABLE;
    }

    @Override
    public TxnResult mint(UUID to, long amount, TxnReason reason) {
        return TxnResult.UNAVAILABLE;
    }

    /** 这一个旧接口做得到（它只有"够不够"与"扣掉"），但没有流水，所以仍然不推荐。 */
    @Override
    public TxnResult burn(UUID from, long amount, TxnReason reason) {
        return TxnResult.UNAVAILABLE;
    }

    /** <b>没有托管。</b>市场类 App 因此不能用这个提供者。 */
    @Override
    public HoldResult hold(UUID from, UUID beneficiary, long amount, TxnReason reason) {
        return HoldResult.fail(TxnResult.UNAVAILABLE);
    }

    @Override
    public TxnResult release(EscrowId id, TxnReason reason) {
        return TxnResult.UNAVAILABLE;
    }

    @Override
    public TxnResult refund(EscrowId id, TxnReason reason) {
        return TxnResult.UNAVAILABLE;
    }

    /** 市场类 App 装之前查这个：没有托管就不能装（§22.7、§22.8）。 */
    public static boolean supportsEscrow(ICurrencyProvider provider) {
        return !(provider instanceof LegacyWalletProvider);
    }
}
