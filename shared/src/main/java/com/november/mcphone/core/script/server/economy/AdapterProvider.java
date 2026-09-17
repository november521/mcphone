package com.november.mcphone.core.script.server.economy;

import com.november.mcphone.api.economy.Currency;
import com.november.mcphone.api.economy.EscrowId;
import com.november.mcphone.api.economy.HoldResult;
import com.november.mcphone.api.economy.ICurrencyProvider;
import com.november.mcphone.api.economy.TxnReason;
import com.november.mcphone.api.economy.TxnResult;

import java.util.UUID;

/**
 * 桥到服主已经装着的经济模组（施工方案 §22.7 的 adapter 一行）。
 *
 * <h2>本步只交付接口与单测</h2>
 *
 * 真正的目标模组还没定。桥一个具体模组要读它的 API、处理它的版本差异、
 * 还要在它不在场时优雅退化 —— 那些只有拿到目标之后才做得对。
 * 所以这里定的是<b>桥的形状</b>：{@link ExternalWallet} 是那一侧要实现的东西。
 *
 * <h2>托管由我们自己兜</h2>
 *
 * 外部经济模组大多只有"加钱/扣钱/查余额"，没有托管。所以 {@link #hold} 的做法是：
 * <b>从外部钱包扣款，押在我们自己的 {@link EscrowLedger} 里</b>，放款/退款时再加回去。
 * 这样市场类 App 在 adapter 上也能用 —— 与 {@code emc_legacy} 的区别正在这里
 * （那一个连"加钱"都没有，所以押了也放不回去）。
 */
public final class AdapterProvider implements ICurrencyProvider {

    /** 外部经济模组要实现的那一面。<b>只有三件事</b>，多了就桥不动大多数模组。 */
    public interface ExternalWallet {

        /** 现在能不能用（模组在不在场、有没有初始化完）。 */
        boolean available();

        long balance(UUID player);

        /** 加钱。{@code amount} 为正。成功返回 true。 */
        boolean deposit(UUID player, long amount);

        /** 扣钱。{@code amount} 为正，不够要返回 false <b>且不扣</b>。 */
        boolean withdraw(UUID player, long amount);
    }

    private final Currency currency;
    private final ExternalWallet wallet;
    private final EscrowLedger escrow;
    private final long maxBalance;

    public AdapterProvider(Currency currency, ExternalWallet wallet, EscrowLedger escrow, long maxBalance) {
        this.currency = currency;
        this.wallet = wallet;
        this.escrow = escrow;
        this.maxBalance = maxBalance;
    }

    private String id() {
        return currency.id().toString();
    }

    @Override
    public Currency currency() {
        return currency;
    }

    @Override
    public boolean isAvailable() {
        return wallet.available();
    }

    @Override
    public String unavailableReasonKey() {
        return "mcphone.economy.adapter.unavailable";
    }

    @Override
    public long balance(UUID player) {
        return wallet.balance(player);
    }

    @Override
    public boolean allowNegative() {
        return false;
    }

    @Override
    public long maxBalance() {
        return maxBalance;
    }

    @Override
    public TxnResult transfer(UUID from, UUID to, long amount, TxnReason reason) {
        // 两端与金额先判：这两条与"外部钱包在不在"无关（E25）。
        // 这一档是 withdraw + deposit 两次实时操作，自己转自己净效果为零、不造币，
        // 但仍然当场拒 —— 四种 provider 对同一个非法调用要给同一个码
        TxnResult parties = com.november.mcphone.api.economy.Balances.checkParties(from, to);
        if (parties != TxnResult.OK) return parties;
        TxnResult bad = com.november.mcphone.api.economy.Balances.checkAmount(amount);
        if (bad != TxnResult.OK) return bad;
        if (!wallet.available()) return TxnResult.UNAVAILABLE;
        if (!wallet.withdraw(from, amount)) return TxnResult.INSUFFICIENT;
        if (!wallet.deposit(to, amount)) {
            // 外部模组的两步之间崩了：把扣掉的加回去，不留中间态
            wallet.deposit(from, amount);
            return TxnResult.FAILED;
        }
        return TxnResult.OK;
    }

    @Override
    public TxnResult mint(UUID to, long amount, TxnReason reason) {
        if (!wallet.available()) return TxnResult.UNAVAILABLE;
        TxnResult bad = com.november.mcphone.api.economy.Balances.checkAmount(amount);
        if (bad != TxnResult.OK) return bad;
        return wallet.deposit(to, amount) ? TxnResult.OK : TxnResult.FAILED;
    }

    @Override
    public TxnResult burn(UUID from, long amount, TxnReason reason) {
        if (!wallet.available()) return TxnResult.UNAVAILABLE;
        TxnResult bad = com.november.mcphone.api.economy.Balances.checkAmount(amount);
        if (bad != TxnResult.OK) return bad;
        return wallet.withdraw(from, amount) ? TxnResult.OK : TxnResult.INSUFFICIENT;
    }

    /** 从外部钱包扣款，押在我们自己的托管账里 —— 外部模组大多没有托管。 */
    @Override
    public HoldResult hold(UUID from, UUID beneficiary, long amount, TxnReason reason) {
        if (!wallet.available()) return HoldResult.fail(TxnResult.UNAVAILABLE);
        if (from == null || beneficiary == null) return HoldResult.fail(TxnResult.INVALID);
        TxnResult bad = com.november.mcphone.api.economy.Balances.checkAmount(amount);
        if (bad != TxnResult.OK) return HoldResult.fail(bad);
        if (!wallet.withdraw(from, amount)) return HoldResult.fail(TxnResult.INSUFFICIENT);
        return HoldResult.ok(escrow.create(from, beneficiary, id(), amount));
    }

    @Override
    public TxnResult release(EscrowId id, TxnReason reason) {
        return settle(id, true);
    }

    @Override
    public TxnResult refund(EscrowId id, TxnReason reason) {
        return settle(id, false);
    }

    private TxnResult settle(EscrowId id, boolean toBeneficiary) {
        EscrowLedger.Entry e = escrow.get(id);
        if (e == null) return TxnResult.UNKNOWN_ESCROW;
        // 托管号要认货币（E25）
        TxnResult wrongCurrency =
                com.november.mcphone.api.economy.Balances.checkEscrowCurrency(e.currencyId(), id());
        if (wrongCurrency != TxnResult.OK) return wrongCurrency;
        if (e.settled()) return TxnResult.ALREADY_SETTLED;
        if (!wallet.available()) return TxnResult.UNAVAILABLE;
        UUID target = toBeneficiary ? e.beneficiary() : e.owner();
        if (!escrow.settle(id)) return TxnResult.ALREADY_SETTLED;
        return wallet.deposit(target, e.amount()) ? TxnResult.OK : TxnResult.FAILED;
    }
}
