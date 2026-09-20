package com.november.mcphone.core.script.engine;

import com.november.mcphone.MCphone;

/**
 * provider（外部经济实现）在动钱时抛了 {@link ScriptAbort}：<b>保持硬停止，但不记过失</b>
 * （S15h/S15i 任务 10，勘误 E35⑤）。
 *
 * <h2>为什么要单独一个类型</h2>
 *
 * {@link ScriptAbort} 是宿主的硬停止信号，它的处分由 {@link ScriptAbort#strikes()} 决定 ——
 * 那个判据是给<b>脚本自己</b>的行为定的。provider 抛出来的那个 {@code ScriptAbort}
 * 是外部实现的行为，不是脚本自身行为，不该按同一张表罚玩家。
 *
 * <p>而按 message 或"谁抛的"去分辨都不行：message 是脚本与第三方都造得出来的东西。
 * 所以宿主在 {@code CtxBuilder.moneyCall} 那一层把它包成这个类型 —— <b>归因是一次显式的包装</b>，
 * 不是事后猜。
 *
 * <h2>为什么不改成 RuntimeException</h2>
 *
 * 它是从宿主函数里抛出来的，而 Rhino 对 RuntimeException 的处理是
 * <b>脚本的 catch 一次都不进、异常直接穿出去</b>（实测，见 {@link HostError} 的类注释）；
 * 更要紧的是 {@code finally { return }} 能把 RuntimeException 吞掉，
 * 那样一次"不知道钱动没动"的中断就消失了。保持 {@code Error} 血统：吞不掉。
 *
 * <h2>结论一律 UNKNOWN</h2>
 *
 * 钱动没动过<b>不知道</b>（provider 在中途抛的），所以回 UNKNOWN ——
 * 与 {@link OutcomeUnknown} 同一条理由：回 INTERNAL 会让玩家再点一次。
 */
final class ProviderAbort extends ScriptAbort {

    private final String what;

    ProviderAbort(String what, String appId, ScriptAbort cause) {
        // 【不挂 cause】：provider 那个异常的 getStackTrace/getMessage 都可能自己炸，
        // 挂上去之后日志渲染 Caused by 时会把求值线程带走（S15e 那一轮的实测结论）
        super(Reason.HOST, "provider 在动钱时抛了 ScriptAbort：" + what + "（原始原因见上一条日志）");
        this.what = what;
        MCphone.LOGGER.error("[MCphone] ⚠ provider 在 {} 时抛了 ScriptAbort（app={}）：{} —— "
                        + "外部实现的行为，不记过失；钱动没动过不知道，本次回 UNKNOWN",
                what, appId, LogText.filter(describe(cause)));
    }

    /** 动的哪一笔（{@code pay} / {@code hold} / …）。 */
    String what() {
        return what;
    }

    private static String describe(ScriptAbort cause) {
        try {
            return cause.getMessage();
        } catch (Throwable t) {
            return "(取 provider 异常文案时又抛了)";
        }
    }
}
