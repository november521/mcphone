package com.november.mcphone.core.script.engine;

/**
 * 宿主要中断本次求值时抛的唯一硬停止信号（施工方案 §16.4、§16.6、§20.4）。
 *
 * <h2>为什么必须 extends Error，不能是 RuntimeException</h2>
 *
 * <b>实测</b>（Rhino 1.9.1，脚本 {@code function g(){ try { boom(); } finally { return 'FINALLY_WINS'; } } g()}）：
 *
 * <pre>
 * 宿主抛 Error            → Error: HOST_ABORT     （脚本吞不掉）
 * 宿主抛 RuntimeException → FINALLY_WINS          ← 中断被 finally 里的 return 吞了
 * </pre>
 *
 * Rhino 的解释器对 {@code Error} 给 {@code EX_NO_JS_STATE}（catch 与 finally 都轮不到），
 * 对 {@code RuntimeException} 给 {@code EX_FINALLY_STATE} —— finally 会跑，跑到 {@code return}
 * 就把待抛的异常丢了。
 *
 * <p><b>纪律</b>：宿主桥里任何"必须立即终止本次调用"一律抛这个；
 * "业务失败"走 {@code ctx.fail(...)} 的返回值，不走异常；<b>"输入不合法"走
 * {@link HostError}</b>（那个脚本接得住、不记过失）。
 *
 * <h2>本类只留三条硬停止原因（S15h/S15i 收口）</h2>
 *
 * 原先 {@code HOST} 一个原因下混了十个抛出点：脚本类型写错、模块解析、配额、cycle、sealed 空壳、
 * 金额类型。那些<b>全都不是硬停止</b>——它们要么该被脚本接住（{@link HostError}），
 * 要么该在求值入口被翻译掉。现在：
 *
 * <table border="1">
 *   <caption>原因与是否记过失</caption>
 *   <tr><th>原因</th><th>抛在哪</th><th>脚本能不能 catch</th><th>记不记过失</th></tr>
 *   <tr><td>{@link #INSTRUCTIONS}</td><td>{@code ScriptBudget.observeInstructionCount}</td><td>不能</td><td><b>记</b></td></tr>
 *   <tr><td>{@link #WALL_CLOCK}</td><td>{@code ScriptBudget.observeInstructionCount}</td><td>不能</td><td><b>记</b></td></tr>
 *   <tr><td>{@link #STACK}</td><td>{@code HostFn} 的宿主桥重入</td><td>不能</td><td><b>记</b></td></tr>
 *   <tr><td>{@link #SIZE}</td><td>没有抛出点（尺寸闸走 {@link HostError}）</td><td>—</td><td>不记</td></tr>
 *   <tr><td>{@link #RETAINED}</td><td>没有抛出点，见 {@link #RETAINED} 的说明</td><td>不能</td><td>—</td></tr>
 *   <tr><td>{@link #HOST}</td><td>过渡用别名，只剩"脚本把玩家输入当路径用"那一处越权尝试</td><td>不能</td><td><b>记</b></td></tr>
 * </table>
 *
 * <p>判据集中在 {@link #strikes()} 一处。<b>不许</b>在调用点各判各的、也不许靠 message 猜。
 * 唯一的例外是 {@link ProviderAbort}：那是 provider（外部实现）抛出来的，
 * 继承本类只为共用"硬停止"这一半，处分由 {@code RhinoEvaluator} 单独给它 {@code NONE}。
 *
 * <p><b>还有一条</b>：宿主函数不许用 {@code FunctionObject} 定义 —— 它把 RuntimeException
 * 转成脚本 catch 得到的 {@code InternalError}。一律 {@code LambdaFunction}。
 */
public class ScriptAbort extends Error {

    // 不是 final：{@link ProviderAbort} 要继承它来把"provider 抛的"与"脚本自己的"分开。
    // 那个区分只影响处分（前者不记过失），硬停止的语义完全一样

    /** 为什么中断。进审计，不给客户端。 */
    public enum Reason {
        /** 指令预算用完。<b>记过失</b>。 */
        INSTRUCTIONS,
        /** 墙钟用完。<b>记过失</b>。 */
        WALL_CLOCK,
        /**
         * 边界尺寸超限（字符串 > 64 KiB 或数组 > 4096）。
         *
         * <p><b>当前没有抛出点</b>（与 {@link #RETAINED} 一样）：尺寸闸 {@code SizeGate} 抛的是
         * {@link HostError} —— 脚本接得住、接住之后能继续，不记过失（E31/E32）。
         * 保留这个常量是为了让 {@link #strikes()} 那张表保持完整、也让审计表能一行一行对上；
         * <b>不要</b>按"会抛 SIZE"写审计表。
         */
        SIZE,
        /** 宿主桥重入太深（脚本靠 {@code valueOf}/{@code toString} 回调重入宿主）。<b>记过失</b>。 */
        STACK,
        /**
         * 跨调用驻留超限。
         *
         * <p><b>当前没有抛出点</b>：驻留超限走 {@code AppScope.sweepRetained()} 的返回值，
         * 调用方（{@code RhinoEvaluator}）据此记一次过失。<b>不要</b>按"会抛 RETAINED"写审计表，
         * 也不要在本步新建抛出点。
         */
        RETAINED,
        /**
         * 别的宿主侧拒绝。<b>只剩一处</b>：{@code ScriptModules} 里脚本把玩家输入当包内路径用
         * （越权尝试）。类型不对、配额、认不出的枚举值现在都是 {@link HostError}。
         */
        HOST
    }

    private final Reason reason;
    private final String detail;

    /** 不填栈：中断每秒可能发生很多次，而栈对定位没用（位置在 detail 里）。 */
    public ScriptAbort(Reason reason, String detail) {
        super(reason + ": " + detail, null, false, false);
        this.reason = reason;
        this.detail = detail;
    }

    public Reason reason() {
        return reason;
    }

    /** 不含原因前缀的说明。翻译成 {@link HostError} 时要把原来那份说明原样带走。 */
    public String detail() {
        return detail;
    }

    /**
     * 这一次中断算不算脚本自己的过失（施工方案 §20.4：熔断只统计脚本自身行为）。
     *
     * <p>记过失的四类：指令/时间预算、重入过深、越权尝试、脚本自身未接住的内部错误
     * （最后那类不在这里 —— 它走 {@code RhinoException}，见 {@code RhinoEvaluator}）。
     *
     * <p>不记的：尺寸、驻留重建（宿主策略，不是脚本行为）。
     */
    public boolean strikes() {
        return switch (reason) {
            case INSTRUCTIONS, WALL_CLOCK, STACK, HOST -> true;
            case SIZE, RETAINED -> false;
        };
    }
}
