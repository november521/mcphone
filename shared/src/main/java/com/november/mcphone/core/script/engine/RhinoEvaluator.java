package com.november.mcphone.core.script.engine;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.net.ScriptErrorCode;
import com.november.mcphone.core.script.server.ActionEvaluator;
import com.november.mcphone.core.script.server.ScriptWorkers;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 用 Rhino 跑一次动作（施工方案 §16、§15.2）。接的是 S12 留的 {@link ActionEvaluator} 口子。
 *
 * <h2>求值跑在 worker 上，不在主线程</h2>
 *
 * 理由不是"20 ms 占 40% 的 tick"，是 <b>20 ms 根本拦不住</b>：Rhino 的指令观察器只在分支点触发，
 * 单个原生操作里一次都不回调。实测 {@code new Int8Array(2e8)} 94 毫秒、
 * {@code JSON.stringify(16M 串)} 419 毫秒，全程不可中断。放主线程就是几个 tick 的硬卡顿，
 * 而且没有任何闸拦得住 —— {@link SizeGate} 与白名单把已知的那些堵上了，残余风险仍在。
 *
 * <p>{@code ctx.shared.*} 的权威是 {@link SharedState} 里的 ConcurrentHashMap，
 * {@code compareAndSet} 就在 worker 上做（§20.4 要的不变量是原子性，不是"在主线程"）。
 * 主线程只在落地时把脏键连同幂等账本写进同一次 setDirty。
 *
 * <p><b>{@code onDone} 仍然只在主线程调，一次且仅一次</b> —— 这是 {@link ActionEvaluator} 的契约。
 */
public final class RhinoEvaluator implements ActionEvaluator {

    /** Internal-only completion disposition; it is never exposed to scripts or the wire. */
    public enum Disposition { NONE, RESET, STRIKE }

    public record Completion(Outcome outcome, Disposition disposition) {
        Completion andStrike() {
            return disposition == Disposition.STRIKE ? this : new Completion(outcome, Disposition.STRIKE);
        }
    }

    private final Map<String, AppScope> apps;
    private final StrikeTracker strikes;
    private final CtxBuilder.Backends backends;
    private final Consumer<Runnable> mainThread;

    /**
     * @param apps       appId → 那个 App 的 scope
     * @param mainThread 把一个活儿丢回主线程。生产环境是 {@code server::execute}
     */
    public RhinoEvaluator(Map<String, AppScope> apps, StrikeTracker strikes,
                          CtxBuilder.Backends backends, Consumer<Runnable> mainThread) {
        this.apps = apps;
        this.strikes = strikes;
        this.backends = backends;
        this.mainThread = mainThread;
    }

    @Override
    public boolean submit(Request request, Consumer<Outcome> onDone) {
        AppScope app = apps.get(request.appId());
        if (app == null) {
            mainThread.accept(() -> onDone.accept(Outcome.fail(ScriptErrorCode.NOT_DEPLOYED)));
            return true;
        }
        if (!strikes.allowed(request.appId(), request.player().uuid())) {
            // 连续超预算之后的冷却期（§16.6）。不是限流，所以不给 retryAfterMs 走 RATE_LIMITED
            mainThread.accept(() -> onDone.accept(Outcome.fail(ScriptErrorCode.INTERNAL)));
            return true;
        }
        // 队列满时返回 false，onDone 不会被调 —— 背压要传回准入那一层（ActionEvaluator 的契约）
        return ScriptWorkers.submit(() -> {
            Completion completion = evaluateSafely(app, request);
            try {
                mainThread.accept(() -> land(request, completion, onDone));
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable dispatch) {
                // 服务器正在停：主线程执行器（server::execute）拒绝投递。onDone 的契约是"主线程上恰好一次"，
                // 此时只能降级，但【绝不静默丢，也不许丢真实结论】—— 直接把这次求值的 Completion 走 land()：
                // land 的 finally 一定会调 onDone，于是账本那条 RESERVED 结清、钱已动⇒UNKNOWN 也照常生效（M1）。
                // 代价：这一次 onDone 不在主线程，且 apply(处分) 会因归属断言在 worker 上失败（被 catch 住）；
                // 停服窗口内可接受，日志留痕。C1：这条口子把"两张表只在主线程读写"的纪律在停服窗口里开了缝 ——
                // 所以【停服窗口里不许再有人改 DeploymentData/AuthorityData/账本】（热重载、stopping 时保存都不行）。
                MCphone.LOGGER.error("[MCphone] ⚠ 完成回调投递失败（服务器正在停？），改在 worker 上直接落地 app={} action={}",
                        request.appId(), request.actionId(), dispatch);
                try {
                    land(request, completion, onDone);
                } catch (Throwable t) {
                    // 归属断言（StrikeTracker 只许主线程）会在这里抛；onDone 已在 land 的 finally 里用真实结论调过
                    MCphone.LOGGER.error("[MCphone] 降级落地收尾（处分跳过，结论已回）app={} action={}",
                            request.appId(), request.actionId(), t);
                }
            }
        });
    }

    /** Last worker boundary: even a bug in failure classification must not kill the worker. */
    private Completion evaluateSafely(AppScope app, Request request) {
        try {
            return evaluate(app, request);
        } catch (Throwable failure) {
            try {
                MCphone.LOGGER.error("[MCphone] evaluator boundary contained a failure app={} action={}",
                        LogText.filter(request.appId()), LogText.filter(request.actionId()), safeFailure(failure));
            } catch (Throwable ignored) {
                // Completion still has to be delivered even when logging itself is unavailable.
            }
            return none(ScriptErrorCode.INTERNAL);
        }
    }

    /** Applies the disposition first, then invokes onDone exactly once, all on the main thread. */
    private void land(Request request, Completion completion, Consumer<Outcome> onDone) {
        try {
            apply(request.appId(), request.player().uuid(), completion.disposition());
        } catch (Throwable failure) {
            MCphone.LOGGER.error("[MCphone] failed to apply script disposition app={} action={}",
                    LogText.filter(request.appId()), LogText.filter(request.actionId()), failure);
        } finally {
            try {
                onDone.accept(completion.outcome());
            } catch (Throwable failure) {
                MCphone.LOGGER.error("[MCphone] script completion callback failed app={} action={}",
                        LogText.filter(request.appId()), LogText.filter(request.actionId()), failure);
            }
        }
    }

    private void apply(String appId, UUID player, Disposition disposition) {
        switch (disposition) {
            case NONE -> { }
            case RESET -> strikes.recordOk(appId, player);
            case STRIKE -> strikes.recordAbort(appId, player);
        }
    }

    /** Worker-side evaluation. It returns data only and never touches StrikeTracker. */
    private Completion evaluate(AppScope app, Request request) {
        ScriptBudget budget = app.budget();
        CtxBuilder.Result result = new CtxBuilder.Result();
        MoneyLedger ledger = new MoneyLedger();
        Completion completion = null;
        boolean entered = false;
        boolean began = false;
        try {
            Context cx = budget.enterContext();
            entered = true;
            budget.begin();
            began = true;
            HostFn.resetDepth();

            try {
                completion = runAction(cx, app, request, result, ledger);
            } catch (Throwable failure) {
                completion = classify(request, failure, ledger);
            }

            completion = sweepRetained(app, request, completion, ledger);
        } catch (Throwable t) {
            completion = classify(request, t, ledger);
        } finally {
            if (began) {
                try {
                    budget.end();
                } catch (Throwable failure) {
                    completion = cleanupFailure(request, completion, ledger, failure);
                }
            }
            try {
                HostFn.resetDepth();
            } catch (Throwable failure) {
                completion = cleanupFailure(request, completion, ledger, failure);
            }
            if (entered) {
                try {
                    Context.exit();
                } catch (Throwable failure) {
                    completion = cleanupFailure(request, completion, ledger, failure);
                }
            }
        }
        // 钱动过的记号要带进 Outcome：落地前重查被拒时靠它决定 UNKNOWN 还是 NOT_AUTHORIZED（S17 约束 6）。
        // 各条路径自己不带这个记号（runAction 只在 code!=OK 时折成 UNKNOWN），在这里统一补上。
        Completion done = completion == null ? none(ScriptErrorCode.INTERNAL) : completion;
        return ledger.moved() && !done.outcome().moneyMoved()
                ? new Completion(done.outcome().withMoneyMoved(), done.disposition())
                : done;
    }

    private Completion runAction(Context cx, AppScope app, Request request, CtxBuilder.Result result,
                                 MoneyLedger ledger) {
        AppScope.Invocation invocation = app.beginInvocation(cx);
        Scriptable call = invocation.callScope();
        Scriptable actions = invocation.actions();
        if (actions == null) return none(ScriptErrorCode.NOT_DEPLOYED);

        Object fn = ScriptableObject.getProperty(actions, request.actionId());
        if (!(fn instanceof Callable action)) return none(ScriptErrorCode.NOT_DEPLOYED);

        ScriptableObject ctx = CtxBuilder.build(cx, call, request.appId(), request.player(), backends, result, ledger);
        action.call(cx, call, actions, new Object[]{ctx});

        ScriptErrorCode code = result.code == null ? ScriptErrorCode.INTERNAL : result.code;
        if (ledger.moved() && code != ScriptErrorCode.OK) return none(ScriptErrorCode.UNKNOWN);
        Outcome outcome = new Outcome(code, result.dataJson.getBytes(StandardCharsets.UTF_8),
                LogText.filter(result.messageKey), result.messageArgs.stream().map(LogText::filter).toList(),
                0, 0, List.of(), ledger.moved());
        return new Completion(outcome, Disposition.RESET);
    }

    private Completion classify(Request request, Throwable failure, MoneyLedger ledger) {
        if (failure instanceof OutcomeUnknown) return none(ScriptErrorCode.UNKNOWN);
        if (failure instanceof ProviderAbort provider) {
            return none(provider.mutating() || ledger.moved() ? ScriptErrorCode.UNKNOWN : ScriptErrorCode.INTERNAL);
        }
        if (failure instanceof HostError host && HostError.classify(host) != null) {
            return ledger.moved() ? none(ScriptErrorCode.UNKNOWN) : none(ScriptErrorCode.INTERNAL);
        }
        if (failure instanceof ScriptAbort abort) {
            MCphone.LOGGER.warn("[MCphone] script aborted app={} action={} detail={}",
                    LogText.filter(request.appId()), LogText.filter(request.actionId()),
                    LogText.filter(abort.getMessage()));
            if (ledger.moved()) return none(ScriptErrorCode.UNKNOWN);
            return new Completion(Outcome.fail(ScriptErrorCode.INTERNAL), strikeFor(abort));
        }
        if (failure instanceof ProviderError
                || failure instanceof com.november.mcphone.core.script.server.economy.ProviderFailure) {
            return ledger.moved() ? none(ScriptErrorCode.UNKNOWN) : none(ScriptErrorCode.INTERNAL);
        }
        if (failure instanceof RhinoException rhino) {
            MCphone.LOGGER.warn("[MCphone] script error app={} action={} line={} detail={}",
                    LogText.filter(request.appId()), LogText.filter(request.actionId()),
                    rhino.lineNumber(), LogText.filter(rhino.details()));
            return ledger.moved() ? none(ScriptErrorCode.UNKNOWN) : strike(ScriptErrorCode.INTERNAL);
        }
        MCphone.LOGGER.error("[MCphone] unexpected script evaluation failure app={} action={}",
                LogText.filter(request.appId()), LogText.filter(request.actionId()), safeFailure(failure));
        return ledger.moved() ? none(ScriptErrorCode.UNKNOWN) : none(ScriptErrorCode.INTERNAL);
    }

    private Completion sweepRetained(AppScope app, Request request, Completion completion, MoneyLedger ledger) {
        try {
            if (app.sweepRetained()) {
                MCphone.LOGGER.warn("[MCphone] retained script scope reset app={}", LogText.filter(app.appId()));
            }
            return completion;
        } catch (Throwable failure) {
            MCphone.LOGGER.error("[MCphone] retained-scope sweep failed app={} action={}",
                    LogText.filter(request.appId()), LogText.filter(request.actionId()), safeFailure(failure));
            return ledger.moved() ? none(ScriptErrorCode.UNKNOWN) : completion.andStrike();
        }
    }

    private Completion cleanupFailure(Request request, Completion completion, MoneyLedger ledger, Throwable failure) {
        MCphone.LOGGER.error("[MCphone] script evaluator cleanup failed app={} action={}",
                LogText.filter(request.appId()), LogText.filter(request.actionId()), safeFailure(failure));
        if (ledger.moved()) return none(ScriptErrorCode.UNKNOWN);
        return completion == null ? strike(ScriptErrorCode.INTERNAL) : completion.andStrike();
    }

    private static Disposition strikeFor(ScriptAbort abort) {
        return switch (abort.reason()) {
            case INSTRUCTIONS, WALL_CLOCK, STACK -> Disposition.STRIKE;
            case SIZE, RETAINED, HOST -> Disposition.NONE;
        };
    }

    private static Throwable safeFailure(Throwable failure) {
        try {
            return com.november.mcphone.core.script.server.economy.ProviderFailure.of(failure);
        } catch (Throwable ignored) {
            return new IllegalStateException("unprintable failure");
        }
    }

    private static Completion none(ScriptErrorCode code) {
        return new Completion(Outcome.fail(code), Disposition.NONE);
    }

    private static Completion strike(ScriptErrorCode code) {
        return new Completion(Outcome.fail(code), Disposition.STRIKE);
    }
}
