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
 * <h2>结果与处分是两件事，而且分在两个线程上（S15h/S15i 任务 2）</h2>
 *
 * 原先 worker 直接在 {@code evaluate} 里调 {@code StrikeTracker.recordOk} /
 * {@code recordAbort} —— 那是<b>线程归属错误</b>：{@code allowed()} 在主线程
 * 准入路径上，四张 {@code HashMap} 被两个线程同时读写，靠 {@code ConcurrentHashMap} 遮着。
 *
 * <p>现在：
 * <ol>
 *   <li>worker 只算出"业务结果 + 内部处分事件"（{@link Completion}），<b>一次都不碰</b> {@code StrikeTracker}；</li>
 *   <li>回到主线程之后，<b>先应用处分、再调 {@code onDone}</b>；</li>
 *   <li>多个完成按回到主线程队列的<b>实际顺序</b>处理，不另加排序缓冲 —— 已进入 worker 的请求
 *       不因为稍后封禁而取消。</li>
 * </ol>
 *
 * <p>处分本身<b>不暴露给脚本</b>：它挂在内部完成对象上，不落在 {@link Outcome} 里、不进 S2C 包。
 *
 * <p><b>{@code onDone} 仍然只在主线程调，一次且仅一次</b> —— 这是 {@link ActionEvaluator} 的契约。
 */
public final class RhinoEvaluator implements ActionEvaluator {

    /**
     * 这一次完成对 {@code StrikeTracker} 的处分。<b>内部事件，脚本看不到。</b>
     *
     * <p>判据是"脚本自身行为"四类（§20.4、E31/E32），映射写在 {@link #evaluateGuarded} 的
     * catch 分支上，每个分支写死一个处分，不靠在异常 message 上猜。
     */
    public enum Disposition {
        /** 不动计数（外部输入、尺寸/配额、服务不可用、结果不明、宿主状态失败）。 */
        NONE,
        /** "连续超预算"清零（脚本正常跑完）。 */
        RESET,
        /** 记一次过失，达到阈值就禁这个玩家。 */
        STRIKE
    }

    /** 一次求值的内部完成：给客户端的结论 + 只给主线程看的处分。 */
    public record Completion(Outcome outcome, Disposition disposition) {

        /** 在原来的处分之上再叠加一次"记过失"（取更强的那个）。 */
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
        // 【准入也在主线程域】：submit 由主线程的落地阶段调用，allowed() 与下面那句 apply 是同一条线程
        if (!strikes.allowed(request.appId(), request.player().uuid())) {
            // 连续超预算之后的冷却期（§16.6）。不是限流，所以不给 retryAfterMs 走 RATE_LIMITED
            mainThread.accept(() -> onDone.accept(Outcome.fail(ScriptErrorCode.INTERNAL)));
            return true;
        }
        // 队列满时返回 false，onDone 不会被调 —— 背压要传回准入那一层（ActionEvaluator 的契约）
        return ScriptWorkers.submit(() -> {
            Completion done = evaluate(app, request);
            mainThread.accept(() -> land(request, done, onDone));
        });
    }

    /**
     * 主线程落地：<b>先应用处分，再调 onDone</b>，且 onDone 恰好一次。
     *
     * <p>{@code finally} 而不是"先记后调"两句：记处分这一步本身出错（比如有人把
     * {@code StrikeTracker} 挪到了别的线程上，它的归属断言会抛）也不许把这次完成整个吞掉 ——
     * 吞掉等于 §15.1 那 4 个并发槽永久烧掉一个。
     */
    private void land(Request request, Completion done, Consumer<Outcome> onDone) {
        try {
            apply(request.appId(), request.player().uuid(), done.disposition());
        } finally {
            try {
                onDone.accept(done.outcome());
            } catch (Throwable t) {
                // onDone 是契约，但它由别人实现。哪一天它在主线程上抛了，
                // 那笔账只该是"这一次回包没送出去"，不该再往上传 ——
                // 往上传的后果是执行这条 lambda 的线程（生产里是 tick 线程）出事
                MCphone.LOGGER.error("[MCphone] onDone 自己抛了 app={} action={}（这一次回包作废，不影响处分）",
                        request.appId(), request.actionId(), t);
            }
        }
    }

    /** 只许在这个方法（以及它唯一的调用者 {@link #land}）里碰 {@code StrikeTracker}：两者都在主线程上。 */
    private void apply(String appId, UUID player, Disposition disposition) {
        switch (disposition) {
            case NONE -> { }
            case RESET -> strikes.recordOk(appId, player);
            case STRIKE -> {
                boolean banned = strikes.recordAbort(appId, player);
                if (banned) {
                    MCphone.LOGGER.warn("[MCphone] 脚本过失达到 {} 次，已禁用 app={} 的这个玩家 {} 分钟",
                            StrikeTracker.PLAYER_STRIKES, appId, StrikeTracker.PLAYER_BAN_MS / 60_000);
                }
            }
        }
    }

    /**
     * 在 worker 线程上跑。<b>任何异常都要收敛成一个 {@link Completion}，不许漏出去</b>，
     * 而且整个过程里一次都不许碰 {@code StrikeTracker}。
     */
    private Completion evaluate(AppScope app, Request request) {
        // 这一次求值的"钱动过没有"的账（E35③）。每个请求一个，跟着 ctx 走 ——
        // 【不能是 ThreadLocal】：provider 回主线程跑，账必须跨线程统一（见 MoneyLedger）
        MoneyLedger ledger = new MoneyLedger();
        return sweepRetained(app, request, evaluateGuarded(app, request, ledger), ledger);
    }

    /** 真正的求值 + 异常分类。 */
    private Completion evaluateGuarded(AppScope app, Request request, MoneyLedger ledger) {
        ScriptBudget budget = app.budget();
        CtxBuilder.Result result = new CtxBuilder.Result();
        Context cx = budget.enterContext();
        try {
            budget.begin();
            HostFn.resetDepth();

            Scriptable call = app.callScope(cx);
            Scriptable actions = app.actions(cx);
            if (actions == null) return noStrike(ScriptErrorCode.NOT_DEPLOYED);

            Object fn = ScriptableObject.getProperty(actions, request.actionId());
            if (!(fn instanceof Callable action)) return noStrike(ScriptErrorCode.NOT_DEPLOYED);

            ScriptableObject ctx = CtxBuilder.build(cx, call, request.appId(), request.player(), backends, result, ledger);
            action.call(cx, call, actions, new Object[]{ctx});

            ScriptErrorCode code = result.code == null ? ScriptErrorCode.INTERNAL : result.code;
            Outcome outcome = new Outcome(code, result.dataJson.getBytes(StandardCharsets.UTF_8),
                    result.messageKey, result.messageArgs, 0, 0, List.of());
            return new Completion(outcome, Disposition.RESET);

        } catch (HostError host) {
            // 【宿主校验失败：脚本接得住的那一类跑到这里，说明它没接住】（S15h/S15i 任务 3）
            // 不记过失：类型不对、越界、配额、认不出的枚举值都是"这次调用不做"，
            // 不是脚本在攻击宿主（E31/E32）。归因按【接住它的是哪个 catch 分支】判，不看 message
            MCphone.LOGGER.debug("[MCphone] 脚本没接住的宿主校验失败 app={} action={}：{}",
                    request.appId(), request.actionId(), LogText.filter(host.details()));
            return noStrike(ScriptErrorCode.INTERNAL);

        } catch (ProviderAbort provider) {
            // 【provider 自己抛的 ScriptAbort 不记过失】（E35⑤）：外部实现的行为，不是脚本自身行为。
            // 钱动没动过不知道 → UNKNOWN。归因靠 ProviderAbort 这个宿主包出来的类型，不看 message
            MCphone.LOGGER.warn("[MCphone] provider 在动钱时中断了 app={} action={} what={}，已回 UNKNOWN",
                    request.appId(), request.actionId(), LogText.filter(provider.what()));
            return noStrike(ScriptErrorCode.UNKNOWN);

        } catch (ScriptAbort abort) {
            return fromAbort(request, abort, ledger);

        } catch (OutcomeUnknown unknown) {
            // 钱可能动了一半：回 INTERNAL 玩家会再点一次、可能多付，所以回 UNKNOWN（客户端绝不自动重试）。不是脚本的错，不记过失
            MCphone.LOGGER.warn("[MCphone] 货币调用结果不明 app={} action={}，已回 UNKNOWN", request.appId(), request.actionId());
            return noStrike(ScriptErrorCode.UNKNOWN);

        } catch (ProviderError provider) {
            // provider 在不动钱的调用里抛了 Error：换成替身、保持 Error 让脚本吞不掉。
            // 【不记过失】（E35⑤）：外部实现的行为，不是脚本自身行为
            MCphone.LOGGER.warn("[MCphone] provider 出错 app={} action={}：{}",
                    request.appId(), request.actionId(), LogText.filter(provider.getMessage()));
            return noStrike(ScriptErrorCode.INTERNAL);

        } catch (RhinoException e) {
            // 脚本自己抛的、而且没接住 —— 四类里"脚本自身未接住的内部错误"，【记过失】
            MCphone.LOGGER.warn("[MCphone] 脚本出错 app={} action={} 第 {} 行: {}（记一次过失）",
                    request.appId(), request.actionId(), e.lineNumber(), LogText.filter(e.details()));
            return strike(ScriptErrorCode.INTERNAL);

        } catch (VirtualMachineError fatal) {
            // OOM / StackOverflowError。§16.6 明写它们【不在"catch 就能恢复"的保证范围内】——
            // 这里兜住只是为了不让一个 worker 线程静默死掉，不是说状态还是好的。
            // 【不记过失】：虚拟机级别的失败是不是脚本写出来的分不出来，罚错人比不罚更糟（E31/E32）
            MCphone.LOGGER.error("[MCphone] 脚本求值碰上虚拟机级别的错误 app={} action={}",
                    request.appId(), request.actionId(), fatal);
            return noStrike(ScriptErrorCode.INTERNAL);

        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 脚本求值出了预期外的问题 app={} action={}",
                    request.appId(), request.actionId(), t);
            return noStrike(ScriptErrorCode.INTERNAL);

        } finally {
            budget.end();
            HostFn.resetDepth();
            Context.exit();
        }
    }

    /**
     * 硬停止的处分分类。
     *
     * <p>记过失的四类里，这里能出三类：预算（{@link ScriptAbort.Reason#INSTRUCTIONS}、
     * {@link ScriptAbort.Reason#WALL_CLOCK}）、重入（{@link ScriptAbort.Reason#STACK}）、
     * 越权尝试（{@link ScriptAbort.Reason#HOST}，现在只剩 {@code ScriptModules} 那一条）。
     * 尺寸（{@link ScriptAbort.Reason#SIZE}）与驻留（{@link ScriptAbort.Reason#RETAINED}）
     * 当前都没有抛出点，{@link ScriptAbort#strikes()} 仍把它们标成不记过失。
     * 判据集中在那一处，这里不重复判。
     *
     * <p><b>结果不明优先于中断</b>（E35③）：这一次求值里钱已经动过的话，无论因为什么被掐，
     * 最终结论都是 {@code UNKNOWN} —— 回 INTERNAL 会让玩家再点一次，那就是多付一次。
     */
    private Completion fromAbort(Request request, ScriptAbort abort, MoneyLedger ledger) {
        // 审计里带原因与 App，【不给客户端 Java 栈】（§16.6）
        MCphone.LOGGER.warn("[MCphone] 脚本中断 app={} action={} {}{}",
                request.appId(), request.actionId(), LogText.filter(abort.getMessage()),
                abort.strikes() ? "（记一次过失）" : "（不记过失）");

        if (ledger.moved()) {
            MCphone.LOGGER.warn("[MCphone] 这一次求值里钱已经动过，中断不改变结论：仍回 UNKNOWN app={} action={}",
                    request.appId(), request.actionId());
            return noStrike(ScriptErrorCode.UNKNOWN);
        }
        return new Completion(Outcome.fail(ScriptErrorCode.INTERNAL),
                abort.strikes() ? Disposition.STRIKE : Disposition.NONE);
    }

    /**
     * 跨调用驻留的清扫（E35④）。
     *
     * <p><b>它必须在有 Context 的域内跑。</b>原先 {@code AppScope.sweepRetained()} 在
     * {@code Context.exit()} <b>之后</b>的 {@code finally} 里被调 —— 而它会读 scope 的顶层属性，
     * 脚本一个 {@code Object.defineProperty(globalThis, 'g', {get: function () { throw ... }})}
     * 就能让它在没有 Context 的线程状态里抛。那个异常从 {@code finally} 里穿出去，
     * worker 线程当场死掉，{@code onDone} 一次都不会被调，准入那一层的并发槽永久烧掉一个。
     *
     * <p>现在：<b>任何 {@code Throwable} 都不许从这里逃出去</b>，失败按"脚本自身未接住的内部错误"
     * 记一次过失（清扫超限本身不记 —— 那是宿主的驻留策略）。
     *
     * <p>清扫失败<b>不改变已经算出来的结论</b>：结果与处分分开之后，这里只能叠加过失，
     * 不能把 OK 改成 INTERNAL（那会让一次已经成功的动作被玩家重做）。
     */
    private Completion sweepRetained(AppScope app, Request request, Completion done, MoneyLedger ledger) {
        try {
            if (app.sweepRetained()) {
                MCphone.LOGGER.warn("[MCphone] {} 的后端跨调用驻留超限，scope 已重建（不记过失）", app.appId());
            }
            return done;
        } catch (VirtualMachineError fatal) {
            MCphone.LOGGER.error("[MCphone] 驻留清扫碰上虚拟机级别的错误 app={}", app.appId(), fatal);
            return done.andStrike();
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 驻留清扫失败 app={} action={}（按脚本自身错误记一次过失）",
                    request.appId(), request.actionId(), t);
            return done.andStrike();
        }
    }

    private static Completion noStrike(ScriptErrorCode code) {
        return new Completion(Outcome.fail(code), Disposition.NONE);
    }

    private static Completion strike(ScriptErrorCode code) {
        return new Completion(Outcome.fail(code), Disposition.STRIKE);
    }
}
