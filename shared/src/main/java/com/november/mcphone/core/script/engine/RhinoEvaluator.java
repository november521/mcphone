package com.november.mcphone.core.script.engine;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.net.ScriptErrorCode;
import com.november.mcphone.core.script.server.ActionEvaluator;
import com.november.mcphone.core.script.server.ScriptWorkers;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.List;
import java.util.Map;
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
            Outcome outcome = evaluate(app, request);
            mainThread.accept(() -> onDone.accept(outcome));
        });
    }

    /** 在 worker 线程上跑。<b>任何异常都要收敛成一个 Outcome，不许漏出去。</b> */
    private Outcome evaluate(AppScope app, Request request) {
        ScriptBudget budget = app.budget();
        CtxBuilder.Result result = new CtxBuilder.Result();
        Context cx = budget.enterContext();
        try {
            budget.begin();
            HostFn.resetDepth();

            Scriptable call = app.callScope(cx);
            Scriptable actions = app.actions(cx);
            if (actions == null) return Outcome.fail(ScriptErrorCode.NOT_DEPLOYED);

            Object fn = ScriptableObject.getProperty(actions, request.actionId());
            if (!(fn instanceof Callable action)) return Outcome.fail(ScriptErrorCode.NOT_DEPLOYED);

            ScriptableObject ctx = CtxBuilder.build(cx, call, request.appId(), request.player(), backends, result);
            action.call(cx, call, actions, new Object[]{ctx});

            strikes.recordOk(request.appId(), request.player().uuid());
            ScriptErrorCode code = result.code == null ? ScriptErrorCode.INTERNAL : result.code;
            return new Outcome(code, result.dataJson.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    result.messageKey, result.messageArgs, 0, 0, List.of());

        } catch (ScriptAbort abort) {
            // 超预算 / 超尺寸 / 重入过深。审计里有原因与 App，【不给客户端 Java 栈】（§16.6）
            boolean banned = strikes.recordAbort(request.appId(), request.player().uuid());
            MCphone.LOGGER.warn("[MCphone] 脚本中断 app={} action={} {}{}",
                    request.appId(), request.actionId(), abort.getMessage(),
                    banned ? "（该玩家的后端已禁用 5 分钟）" : "");
            return Outcome.fail(ScriptErrorCode.INTERNAL);

        } catch (OutcomeUnknown unknown) {
            // 钱可能动了一半：回 INTERNAL 玩家会再点一次、可能多付，所以回 UNKNOWN（客户端绝不自动重试）。不是脚本的错，不记过失
            MCphone.LOGGER.warn("[MCphone] 货币调用结果不明 app={} action={}，已回 UNKNOWN", request.appId(), request.actionId());
            return Outcome.fail(ScriptErrorCode.UNKNOWN);

        } catch (org.mozilla.javascript.RhinoException e) {
            // 脚本自己抛的。审计里带行号，客户端只拿到 INTERNAL（§16.6）
            MCphone.LOGGER.warn("[MCphone] 脚本出错 app={} action={} 第 {} 行: {}",
                    request.appId(), request.actionId(), e.lineNumber(), e.details());
            return Outcome.fail(ScriptErrorCode.INTERNAL);

        } catch (Throwable t) {
            // OOM / StackOverflowError 落在这里。§16.6 明写它们【不在"catch 就能恢复"的保证范围内】——
            // 这里兜住只是为了不让一个 worker 线程静默死掉，不是说状态还是好的
            MCphone.LOGGER.error("[MCphone] 脚本求值出了预期外的问题 app={}", request.appId(), t);
            return Outcome.fail(ScriptErrorCode.INTERNAL);

        } finally {
            budget.end();
            HostFn.resetDepth();
            Context.exit();
            if (app.sweepRetained()) {
                MCphone.LOGGER.warn("[MCphone] {} 的后端跨调用驻留超限，scope 已重建", app.appId());
            }
        }
    }
}
