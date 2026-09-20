package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.net.ScriptErrorCode;

import java.util.List;
import java.util.function.Consumer;

/**
 * 跑一次脚本动作（施工方案 §15.5 的 worker 那一层）。<b>S13 用 Rhino 实现，本步只定接口。</b>
 *
 * <h2>为什么不是 {@code Outcome evaluate(Request, Snapshot)} 这种纯函数</h2>
 *
 * 那个形状 S13 装不进去。§16.5 给脚本的 {@code ctx} 里全是<b>读写且要持久化</b>的东西
 * （{@code ctx.store.setLong}、{@code ctx.shared.compareAndSet}、{@code ctx.currency.pay}），
 * 而 §20.4 写死"{@code compareAndSet} 在主线程上做"。脚本是命令式的：
 * {@code if (ctx.shared.compareAndSet(...)) ...} 的返回值决定后面走哪一支 ——
 * 没法拆成"worker 上纯求值 → 主线程落地意图"两段，第一段要拿到第二段的结果才能继续。
 *
 * <p>所以<b>接口不承诺线程</b>，把线程归属关进实现：实现自己在 worker 与主线程之间来回，
 * 只保证 {@code onDone} 在主线程上被调到<b>一次且仅一次</b>。
 *
 * <h2>这是内部接口，可以改签名</h2>
 *
 * {@code MCphoneApi} 那五条"只增不减"的规矩第五条写着"只管 api 包"。
 * 这个类住在 {@code core/script/server/}，没有外部实现者。
 * <b>但它不许搬到 {@code api/} 下</b> —— 搬过去就等于把一个还会变的形状冻起来。
 */
public interface ActionEvaluator {

    /**
     * 提交一次求值。
     *
     * @param onDone <b>收下了就保证在服务器主线程上被调到一次且仅一次</b>。实现不许调两次，
     *               也不许一次都不调 —— 一次都不调会把 §15.1 那 4 个并发槽永久烧掉一个
     * @return 收没收下。<b>false = 忙，{@code onDone} 不会被调</b>，调用方据此回
     *         {@link ScriptErrorCode#RATE_LIMITED}。
     *         有界队列在实现那一侧（{@link ScriptWorkers}），但背压要传回准入这一层 ——
     *         不传的话队列满时只能静默丢弃，客户端干等到超时，那 4 个并发槽就白烧了
     */
    boolean submit(Request request, Consumer<Outcome> onDone);

    /**
     * @param seq 这一次求值的序号，只用于日志与排查。<b>不是幂等键</b>
     */
    record Request(String appId, String actionId, byte[] params,
                   PlayerSnapshot player, String deployRev, long seq) {
    }

    /**
     * 求值的结论。
     *
     * <p>{@code retryAfterMs} 与 {@code stateRevision} 是 §15.3 的 S2C 字段，
     * <b>它们的值只有业务层知道</b>（{@code COOLDOWN} 的 nextAt、{@code EXHAUSTED} 的余量、
     * §20.6 的 revision）。不放在这里，S13 到货时就要改这个已经合并的签名。
     */
    record Outcome(ScriptErrorCode code, byte[] data, String messageKey, List<String> messageArgs,
                   long retryAfterMs, long stateRevision, List<ActionIntent> intents, boolean moneyMoved) {

        public Outcome {
            if (code == null) code = ScriptErrorCode.INTERNAL;
            if (data == null) data = new byte[0];
            if (messageKey == null) messageKey = code.defaultMessageKey();
            messageArgs = messageArgs == null ? List.of() : List.copyOf(messageArgs);
            intents = intents == null ? List.of() : List.copyOf(intents);
        }

        /**
         * 这一次求值里<b>钱已经动过</b>（provider 返回过结果）。落地前重查被拒时要据此回
         * {@code UNKNOWN} 而不是 {@code NOT_AUTHORIZED} —— 回后者玩家会以为"没动、重试一下"，
         * 而钱可能已经付了（E35③、§15.9）。
         */
        public Outcome withMoneyMoved() {
            return moneyMoved ? this
                    : new Outcome(code, data, messageKey, messageArgs, retryAfterMs, stateRevision, intents, true);
        }

        public static Outcome ok(byte[] data, long stateRevision, List<ActionIntent> intents) {
            return new Outcome(ScriptErrorCode.OK, data, "", List.of(), 0, stateRevision, intents, false);
        }

        public static Outcome fail(ScriptErrorCode code) {
            return new Outcome(code, new byte[0], code.defaultMessageKey(), List.of(), 0, 0, List.of(), false);
        }
    }
}
