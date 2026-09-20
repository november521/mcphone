package com.november.mcphone.core.script.engine;

/**
 * 一次求值里"钱动过没有"的账（S15h/S15i 任务 9，勘误 E35③）。
 *
 * <h2>它记的不是"谁动了钱"，是"结果已经不可无条件相信了"</h2>
 *
 * 一旦 {@code ctx.currency.pay/hold/release/refund/mint/burn} 里任何一个真的走到了 provider
 * 并拿到了结果，这次求值的最终结论就<b>不许</b>再被降级成 {@code INTERNAL}：
 * 玩家看到 INTERNAL 会再点一次，那就是多付一次。所以：
 *
 * <ol>
 *   <li>标志置位之后，本次求值里<b>任何新的动钱调用直接拒绝</b>（{@link #rejectFurther}）：
 *       一次求值动两笔钱，出问题时没法自动判定该退哪一笔。口径写在这里，不在调用点各写一遍。</li>
 *   <li>标志置位之后，即使求值因为预算/重入被中断（{@link ScriptAbort}），
 *       结论也是 {@code UNKNOWN} 而不是 {@code INTERNAL} —— 见 {@code RhinoEvaluator}。</li>
 * </ol>
 *
 * <h2>为什么不是 ThreadLocal</h2>
 *
 * <b>实测踩过</b>：provider 是<b>回服务端主线程</b>执行的（{@code CurrencyGateway}），
 * 于是 {@code movedMoney()} 记在 <b>main</b> 线程上，而脚本下一次调用 {@code moved()} 时
 * 在 <b>worker</b> 上读 —— 两个 {@code ThreadLocal} 是两本账，"本次已经动过钱"永远读不到，
 * 标志形同虚设。所以它是一次求值一个的<b>显式对象</b>，由 {@code RhinoEvaluator} 建出来、
 * 经 {@code CtxBuilder.build} 发给 ctx 里的每一个货币方法。
 *
 * <p>顺带一个好处：因为它是本次求值的局部对象，跨线程也读得到同一个实例 ——
 * 一条<b>更严</b>的口径自然成立：{@code pay} 从主线程返回之后，worker 上任何新的动钱调用
 * 都会被拒（不管它在哪条线程上发生）。
 *
 * <p><b>不许</b>用它替代任何真正的锁或线程归属：它只管本次求值内的一致性，
 * 跨请求的权威状态在 {@code StrikeTracker}（主线程）与 {@code CurrencyGateway}（网关）那里。
 */
public final class MoneyLedger {

    /** [0] = 这一次求值里钱有没有真的动过。volatile：写在主线程、读在 worker。 */
    private final boolean[] flag = new boolean[1];

    /** 开一次账。{@code RhinoEvaluator} 每次求值建一个。 */
    public MoneyLedger() {
    }

    /** 钱动过没有。 */
    public boolean moved() {
        return flag[0];
    }

    /** 记一笔"钱动过了"。只在 provider 真的返回了结果之后调。 */
    void movedMoney() {
        flag[0] = true;
    }

    /**
     * 本次求值里已经动过钱了，还能不能再动一笔？
     *
     * <p>不能时抛脚本接得住的 {@link HostError}，<b>不记过失</b> ——
     * 这是宿主的口径，不是脚本写错了。
     */
    void rejectFurther(String what) {
        if (!moved()) return;
        throw HostError.invalid("本次求值已经动过钱，同一次求值里不许再动第二笔（" + what
                + "）；一次调用只做一笔货币操作，需要多笔就分成多次调用");
    }
}
