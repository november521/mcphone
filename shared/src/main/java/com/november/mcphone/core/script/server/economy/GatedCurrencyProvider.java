package com.november.mcphone.core.script.server.economy;

import com.november.mcphone.api.economy.Currency;
import com.november.mcphone.api.economy.EscrowId;
import com.november.mcphone.api.economy.HoldResult;
import com.november.mcphone.api.economy.ICurrencyProvider;
import com.november.mcphone.api.economy.TxnReason;
import com.november.mcphone.api.economy.TxnResult;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 注册表交出去的都是这一层（{@link CurrencyRegistry}）：provider 的每个方法都经 {@link CurrencyGateway} 在主线程上执行。
 * 调用方拿不到里面那一个 —— 线程模型只有一份，不许有第二条直达 provider 的路。
 *
 * <p>网关拒掉的调用 provider 根本没见到，所以流水里没有这一行；原因在 {@link #unavailableReasonKey()}
 * （这条线程上一次被拒的原因）与网关的限流日志里。
 *
 * <p><b>会动钱的方法只在 provider 还没开始跑时才给 UNAVAILABLE</b>；provider 跑起来之后抛出的 {@link CurrencyUnavailableException}
 * （它自己抛的，或者它里面又调了别的网关被拒）原样抛出去 —— 它可能已经动了一半（外部钱包先记上钱再抛），
 * 变成 UNAVAILABLE 调用方就会当"没动"去重试。按"跑没跑"判断，不按异常从哪来：异常对象会穿过嵌套调用。
 */
public final class GatedCurrencyProvider implements ICurrencyProvider {

    /** 这条线程上、这一种货币上一次被网关拒的原因。每个实例一份：共用的话 A 币被拒，问 B 币也拿到 A 币的原因 */
    private final ThreadLocal<String> lastRefusal = new ThreadLocal<>();

    private final ICurrencyProvider inner;
    private final CurrencyGateway gateway;
    private final String appId;
    private final java.util.function.Function<String,TxnResult> authorization;
    private ScriptCurrencyEscrows escrowBindings;
    private ScriptCurrencyEscrows.Scope escrowScope;
    private SettlementJournal settlements;

    public GatedCurrencyProvider(ICurrencyProvider inner, CurrencyGateway gateway) {
        this(inner,gateway,null,operation->TxnResult.OK);
    }

    private GatedCurrencyProvider(ICurrencyProvider inner,CurrencyGateway gateway,String appId,
                                  java.util.function.Function<String,TxnResult> authorization) {
        this.inner = inner;
        this.gateway = gateway;
        this.appId=appId;this.authorization=authorization;
    }

    GatedCurrencyProvider forScript(String app,java.util.function.Function<String,TxnResult> gate) {
        GatedCurrencyProvider bound=new GatedCurrencyProvider(inner,gateway,app,gate);bound.settlements=settlements;return bound;
    }
    GatedCurrencyProvider settlements(SettlementJournal journal){settlements=journal;return this;}
    GatedCurrencyProvider forScript(ScriptCurrencyEscrows.Scope scope,ScriptCurrencyEscrows bindings,
                                   java.util.function.Function<String,TxnResult> gate) {
        GatedCurrencyProvider bound=forScript(scope.app(),gate);bound.escrowBindings=bindings;bound.escrowScope=scope;return bound;
    }

    /** 只有本桥能创建，明确表示尚未进入 provider。不能与 provider 自己抛出的异常混为 UNKNOWN。 */
    public static final class AuthorizationRefused extends RuntimeException {
        private final TxnResult result;
        private AuthorizationRefused(TxnResult result){super("货币操作的当前授权被拒绝");this.result=result;}
        public com.november.mcphone.core.script.engine.HostError error(){return result==TxnResult.UNAVAILABLE
                ?com.november.mcphone.core.script.engine.HostError.denied(com.november.mcphone.core.script.net.ScriptErrorCode.UNAVAILABLE,"mcphone.script.capability.disabled","货币操作被服主关闭")
                :com.november.mcphone.core.script.engine.HostError.denied(com.november.mcphone.core.script.net.ScriptErrorCode.NOT_AUTHORIZED,"mcphone.script.not_authorized","货币操作授权已经改变");}
    }

    private <T>T authorized(String operation,Supplier<T> effect) {
        TxnResult verdict=authorization.apply(operation);
        if(verdict!=TxnResult.OK)throw new AuthorizationRefused(verdict==TxnResult.NOT_AUTHORIZED?verdict:TxnResult.UNAVAILABLE);
        String previous=CallingApp.current();if(appId!=null)CallingApp.enter(appId);
        try{return effect.get();}
        catch(AuthorizationRefused nested){throw new IllegalStateException("钱包内部的嵌套拒绝不能证明外层尚未修改余额",nested);}
        finally{if(appId!=null)CallingApp.enter(previous);}
    }

    /** 注册表用：查重，以及拆掉别的网关的包装、换成自己的。 */
    ICurrencyProvider inner() {
        return inner;
    }

    // 元数据与构造时定死的两个常量：不可变，不必回主线程

    @Override
    public Currency currency() {
        return inner.currency();
    }

    @Override
    public boolean allowNegative() {
        return inner.allowNegative();
    }

    @Override
    public long maxBalance() {
        return inner.maxBalance();
    }

    @Override
    public boolean isAvailable() {
        try {
            boolean ok = gateway.call(inner::isAvailable);
            lastRefusal.remove();
            return ok;
        } catch (CurrencyUnavailableException e) {
            lastRefusal.set(e.reasonKey());
            return false;
        }
    }

    /** 这条线程上一次被网关拒的原因优先；没被拒就问 provider 自己。 */
    @Override
    public String unavailableReasonKey() {
        String refused = lastRefusal.get();
        if (refused != null) return refused;
        try {
            return gateway.call(inner::unavailableReasonKey);
        } catch (CurrencyUnavailableException e) {
            return e.reasonKey();
        }
    }

    /** 读不到就抛 {@link CurrencyUnavailableException}：返回 0 是静默给错数。 */
    @Override
    public long balance(UUID player) {
        try {
            long v = gateway.call(() -> authorized("balance",()->inner.balance(player)));
            lastRefusal.remove();
            return v;
        } catch (CurrencyUnavailableException e) {
            lastRefusal.set(e.reasonKey());
            throw e;
        }
    }

    @Override
    public TxnResult transfer(UUID from, UUID to, long amount, TxnReason reason) {
        return txn("pay",() -> inner.transfer(from, to, amount, reason));
    }

    @Override
    public TxnResult mint(UUID to, long amount, TxnReason reason) {
        return txn("mint",() -> inner.mint(to, amount, reason));
    }

    @Override
    public TxnResult burn(UUID from, long amount, TxnReason reason) {
        return txn("burn",() -> inner.burn(from, amount, reason));
    }

    @Override
    public HoldResult hold(UUID from, UUID beneficiary, long amount, TxnReason reason) {
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            HoldResult h = gateway.call(() -> authorized("hold",()->{
                ran.set(true);
                return escrowBindings==null?inner.hold(from,beneficiary,amount,reason):escrowBindings.hold(
                        escrowScope,currency().id().toString(),from,beneficiary,amount,()->inner.hold(from,beneficiary,amount,reason));
            }));
            lastRefusal.remove();
            return h;
        } catch (CurrencyUnavailableException e) {
            lastRefusal.set(e.reasonKey());
            if (ran.get()) throw e;
            return HoldResult.fail(TxnResult.UNAVAILABLE);
        }
    }

    @Override
    public TxnResult release(EscrowId id, TxnReason reason) {
        return txn("release",() -> escrowBindings==null?settle("release",id,()->inner.release(id,reason)):escrowBindings.settle(
                escrowScope,currency().id().toString(),id,()->settle("release",id,()->inner.release(id,reason))));
    }

    @Override
    public TxnResult refund(EscrowId id, TxnReason reason) {
        return txn("refund",() -> escrowBindings==null?settle("refund",id,()->inner.refund(id,reason)):escrowBindings.settle(
                escrowScope,currency().id().toString(),id,()->settle("refund",id,()->inner.refund(id,reason))));
    }
    private TxnResult settle(String operation,EscrowId id,Supplier<TxnResult> effect){return settlements==null?effect.get():settlements.settle(currency().id().toString(),operation,id,effect);}

    private TxnResult txn(String operation,Supplier<TxnResult> op) {
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            TxnResult r = gateway.call(() -> authorized(operation,()->{
                ran.set(true);
                return op.get();
            }));
            lastRefusal.remove();
            return r;
        } catch (CurrencyUnavailableException e) {
            lastRefusal.set(e.reasonKey());
            if (ran.get()) throw e;
            return TxnResult.UNAVAILABLE;
        }
    }
}
