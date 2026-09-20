package com.november.mcphone.core.script.engine;

import org.mozilla.javascript.WrappedException;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 宿主校验失败：<b>脚本接得住、不记过失、且归因伪造不出来</b>（施工方案 S15h/S15i，勘误 E31①、E33、E35②）。
 *
 * <h2>它和另外两个失败形态的分工</h2>
 *
 * <table border="1">
 *   <caption>三种失败形态</caption>
 *   <tr><th>形态</th><th>脚本能不能 catch</th><th>记不记过失</th><th>什么时候用</th></tr>
 *   <tr><td>{@link ScriptAbort}</td><td><b>不能</b>（extends Error，Rhino 给 EX_NO_JS_STATE）</td>
 *       <td>看 {@link ScriptAbort#strikes()}</td><td>必须立即终止的结构性边界：预算、重入、越权、原生放大</td></tr>
 *   <tr><td><b>本类</b></td><td><b>能</b></td><td><b>不记</b></td>
 *       <td>宿主对<b>输入</b>的校验失败：类型不对、越界、配额、认不出的枚举值、解析失败</td></tr>
 *   <tr><td>{@link OutcomeUnknown}</td><td><b>不能</b></td><td><b>不记</b></td>
 *       <td>钱可能已经动了一半，结果不明</td></tr>
 * </table>
 *
 * <h2>为什么必须是 {@link WrappedException} 而不是 RuntimeException</h2>
 *
 * <b>实测</b>（Rhino 1.9.1，LambdaFunction 里 {@code throw}，脚本 {@code try { boom() } catch (e) { ... }}）：
 *
 * <pre>
 * 抛 RuntimeException  → 脚本的 catch 一次都不进，异常直接穿出去被宿主接住
 * 抛 RhinoException 子类 → 脚本 catch 得到（e.name === 'InternalError'，e.message 是原文）
 * </pre>
 *
 * 也就是说"脚本接得住"这条要求<b>只能靠 RhinoException 那一支</b>。
 * （{@code HostFn} 的类注释里"FunctionObject 会把 RuntimeException 转成脚本 catch 得到的
 * InternalError"那句是照 §16.5 抄的，本次实测与它不符 —— 见本类所在的 PR 的审计表。）
 *
 * <h2>代价：{@code finally { return }} 能吞掉它</h2>
 *
 * 与 {@link ScriptAbort}（吞不掉）相反，Rhino 给 RhinoException 的是 {@code EX_FINALLY_STATE}，
 * 所以脚本一个 {@code finally { return }} 就能把宿主校验错误丢掉。
 * <b>这在设计上是允许的</b>：本类只用于"这一次调用不做"的拒绝，
 * 而拒绝<b>不产生任何副作用</b>（{@code ExactLong} 在写入之前抛、配额拒绝不落盘），
 * 所以吞掉它的后果只是调用方自己拿不到结果 —— 而返回值会被算成 {@code undefined}，
 * 进不了比较与算术（验收标准里那条"不能作为比较、算术或后续货币调用的值继续流动"）。
 *
 * <h2>归因为什么不能靠 message / 类名 / JS 属性</h2>
 *
 * 那三样脚本都造得出来：它可以 {@code throw new Error('mcphone...')}，也可以给 {@code e.name} 赋值。
 * 按它们判断"这次算不算脚本的错"，脚本就能自己给自己免责或者栽赃。
 *
 * <p>所以归因是<b>宿主侧的一个关联值</b>：{@link #marked} 里的那个私有对象，只有 {@link #of}
 * 造出来的实例有它。这条判据的落笔处是 {@link #classify}，而 {@link RhinoEvaluator} 的分类
 * 走的是"哪个 catch 分支接住的"，不在这里看字符串 —— 两道加起来才叫不可伪造。
 *
 * <h2>message 的写法</h2>
 *
 * {@code "<大写码>: <说明>"}，与桥里既有的 {@code UNAVAILABLE: <key>}、{@code INVALID: <key>}
 * 同一形状。码值见 {@link #INVALID}、{@link #QUOTA}、{@link #UNKNOWN_VALUE}。
 */
public final class HostError extends WrappedException {

    /** 参数值不合法（解析失败、越界、小数、NaN、不是 UUID…）。App 该 {@code ctx.fail('INVALID_ARGUMENT')}。 */
    public static final String INVALID = "INVALID";

    /** 超出配额（存储/shared 的键数、单值、总量）。App 该告诉玩家存不下了。 */
    public static final String QUOTA = "QUOTA";

    /** 认不出的枚举值（如 {@code cycle.label('yearly')}）。 */
    public static final String UNKNOWN_VALUE = "UNKNOWN_VALUE";

    /**
     * 宿主盖的归因标记。
     *
     * <p>键弱引用（不要靠它持有任何东西），值是每个实例一个的私有对象；查的时候按<b>身份</b>比，
     * 不按 equals —— 一个能重写 equals 的类不配当归因依据。
     */
    private static final Map<HostError, Object> marked =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final String code;
    private final String detail;

    private HostError(String code, String detail) {
        // 挂一个自己的 cause：RhinoException 的 printStackTrace 会把整条链打出来，
        // 没有 cause 的话脚本栈与宿主栈在日志里是混在一起的
        super(new IllegalArgumentException(code + ": " + detail));
        this.code = code;
        this.detail = detail;
        marked.put(this, new Object());
    }

    /** 造一个宿主校验错误。{@code detail} 只进日志与开发者排查，客户端拿到的是结果码。 */
    public static HostError of(String code, String detail) {
        return new HostError(code, detail);
    }

    /** 参数不合法。 */
    public static HostError invalid(String detail) {
        return new HostError(INVALID, detail);
    }

    /** 超配额。 */
    public static HostError quota(String detail) {
        return new HostError(QUOTA, detail);
    }

    /** 认不出的枚举值。 */
    public static HostError unknownValue(String detail) {
        return new HostError(UNKNOWN_VALUE, detail);
    }

    /** 结果码，见 {@link #INVALID} / {@link #QUOTA} / {@link #UNKNOWN_VALUE}。 */
    public String code() {
        return code;
    }

    /** 不含码前缀的说明。 */
    public String detail() {
        return detail;
    }

    /** Rhino 会拿它去填脚本侧那个 {@code InternalError} 对象的 {@code message}。 */
    @Override
    public String details() {
        return code + ": " + detail;
    }

    /**
     * 归因探针：<b>这个实例是不是宿主造出来的</b>。
     *
     * <p>返回 null 表示"不是宿主造的"—— 调用方不许退回去看 message、类名或任何 JS 属性。
     * 拿不到标记就是没有归因，仅此而已。
     *
     * <p>注意它<b>不是</b> {@code RhinoEvaluator} 记不记过失的判据（那边按 catch 分支走）；
     * 它是"谁也别想靠字符串冒充宿主错误"这条规矩的落笔处，也给审计与测试一个可断言的入口。
     */
    public static String classify(Throwable t) {
        if (!(t instanceof HostError h)) return null;
        return marked.get(h) == null ? null : h.code;
    }

    /**
     * 把一个 {@link ScriptAbort} 换成脚本接得住的形态 —— 只对"宿主校验"那一类原因有效。
     *
     * <p>认不出来（预算、重入、原生放大那种）返回 null，让调用方照旧把它当硬停止抛出去。
     */
    static HostError fromAbort(ScriptAbort abort) {
        return switch (abort.reason()) {
            case HOST, SIZE -> invalid(abort.detail());
            default -> null;
        };
    }
}
