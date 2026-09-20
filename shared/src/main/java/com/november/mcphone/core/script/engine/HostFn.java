package com.november.mcphone.core.script.engine;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.ArrayList;
import java.util.List;

/**
 * 建宿主函数、读宿主函数的参数（施工方案 §16.5 的桥）。
 *
 * <h2>不许用 ScriptRuntime.toString / toNumber</h2>
 *
 * 那两个会调用脚本对象的 {@code valueOf} / {@code toString}，于是<b>脚本能在宿主方法执行到一半时重入宿主</b>。
 * 实测：无限重入到 852 层才 {@code StackOverflowError}，而 §16.6 明写 StackOverflowError
 * "不在 catch 就能恢复的保证范围内" —— 脚本随手就能造出那个状态，还可能落在一次 CAS 的读与写之间。
 * {@code setMaximumInterpreterStackDepth(64)} 对这条路径无效：每次重入是一次新的解释器调用，深度重新起算。
 *
 * <p>所以这里的读参数函数<b>只收原语，不做转换</b>：给了对象就直接 {@link ScriptAbort}。
 * 再加一道 {@link #depth} 兜底。
 *
 * <h2>一律 LambdaFunction，不许 FunctionObject</h2>
 *
 * 实测 {@code FunctionObject} 会把宿主抛的 RuntimeException 转成脚本 {@code catch} 得到的
 * {@code InternalError}；而中断信号是 {@link ScriptAbort}（extends Error），
 * 两种写法下 {@code catch} 与 {@code finally} 都吞不掉它。
 *
 * <p><b>S15h/S15i 实测更正</b>：后半句按探针重写过。宿主 {@code LambdaFunction} 里
 * <b>抛非 RhinoException 的东西（含 RuntimeException 与一切 Error 子类，也就是 {@link ScriptAbort}）
 * 根本进不了脚本的 catch</b> —— 它直接穿出求值；只有 {@code RhinoException} 子类进得去
 * （那就是 {@link HostError} 之所以继承 {@code WrappedException} 的全部理由）。
 * 所以"不许 FunctionObject"这条纪律照旧（换掉它会把异常形态整个改一遍），
 * 但"RuntimeException 能被脚本 catch 住"这个说法是错的，别照着它设计错误处理。
 * 详见 {@code docs/script-failure-audit.md} 的 §0.1。
 */
public final class HostFn {

    private HostFn() {
    }

    /** 宿主桥的重入上限。正常的桥调用一层就够，留 4 层给"宿主函数里调宿主函数"。 */
    public static final int MAX_HOST_DEPTH = 4;

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    /** 建一个宿主函数：自带重入兜底与参数尺寸闸。 */
    public static LambdaFunction of(Scriptable scope, String name, int arity, Body body) {
        LambdaFunction f = new LambdaFunction(scope, name, arity, (cx, sc, thisObj, args) -> {
            SizeGate.checkAll(args, name + " 的参数");
            int[] d = DEPTH.get();
            if (++d[0] > MAX_HOST_DEPTH) {
                d[0]--;
                throw new ScriptAbort(ScriptAbort.Reason.STACK, name + ": 宿主桥重入超过 " + MAX_HOST_DEPTH + " 层");
            }
            try {
                return body.call(cx, sc, args);
            } finally {
                d[0]--;
            }
        });
        // LambdaFunction 自带一个未密封的 .prototype —— 跨调用复用 scope 时那是驻留点
        f.sealObject();
        return f;
    }

    /** 宿主函数的正文。 */
    @FunctionalInterface
    public interface Body {
        Object call(Context cx, Scriptable scope, Object[] args);
    }

    /** 平账用，每次求值前后各调一次。 */
    public static void resetDepth() {
        DEPTH.get()[0] = 0;
    }

    // ---------------------------------------------------------------- 读参数：只收原语

    public static String str(Object[] args, int i, String where) {
        Object v = at(args, i);
        if (v instanceof CharSequence cs) {
            SizeGate.check(cs, where);
            return cs.toString();
        }
        throw typeError(args, where, i, "字符串");
    }

    /**
     * 精确的 {@code long} 参数。越界、小数、{@code NaN}、{@code Infinity} 一律 {@link HostError}
     * （可接住、不记过失、<b>写前就抛</b>），理由见 {@link ExactLong}。
     */
    public static long exactLong(Object[] args, int i, String where) {
        return ExactLong.of(at(args, i), where);
    }

    public static boolean bool(Object[] args, int i, String where) {
        Object v = at(args, i);
        if (v instanceof Boolean b) return b;
        throw typeError(args, where, i, "布尔");
    }

    /** 一串字符串。元素数受 {@link SizeGate#MAX_ARRAY} 限。 */
    public static List<String> strList(Object[] args, int i, String where) {
        Object v = at(args, i);
        if (!(v instanceof NativeArray arr)) {
            throw typeError(args, where, i, "数组");
        }
        SizeGate.check(arr, where);
        List<String> out = new ArrayList<>();
        for (int k = 0; k < arr.getLength(); k++) {
            Object e = arr.get(k, arr);
            if (!(e instanceof CharSequence cs)) {
                throw HostError.invalid(where + " 的第 " + k + " 个元素要字符串");
            }
            SizeGate.check(cs, where);
            out.add(cs.toString());
        }
        return out;
    }

    /**
     * 类型不对。
     *
     * <p>这里<b>不用</b> {@code ScriptAbort}：类型对不对是宿主对输入的校验，
     * 脚本接得住才好写"这个字段没填就换个分支"；而记过失会连着几次禁玩家、熔断整个 App ——
     * 那是在罚玩家（S15h/S15i，勘误 E31/E32）。
     *
     * <p>给对象就直接拒，<b>不做</b> {@code valueOf} 转换：那等于让脚本在宿主方法执行到一半时重入宿主。
     */
    private static HostError typeError(Object[] args, String where, int i, String want) {
        Object v = at(args, i);
        return HostError.invalid(where + " 第 " + i + " 个参数要" + want
                + "，收到 " + (v == null ? "null" : v.getClass().getSimpleName()));
    }

    public static boolean present(Object[] args, int i) {
        Object v = at(args, i);
        return v != null && v != Undefined.instance;
    }

    private static Object at(Object[] args, int i) {
        return args != null && i < args.length ? args[i] : Undefined.instance;
    }

    // ---------------------------------------------------------------- 建给脚本看的对象

    /**
     * 建一个<b>只能读</b>的宿主对象：断开原型链与父 scope，填完就密封。
     *
     * <p>断原型链是硬要求：留着的话脚本能顺 {@code ctx.xxx.constructor} 往回爬。
     * 实测把一个 Java 对象直接注入（{@code NativeJavaObject}）并放行类访问时，
     * {@code ctx.player.getClass().getClassLoader()} 能拿到 AppClassLoader —— 沙箱当场报废。
     */
    public static ScriptableObject obj(Context cx, Scriptable scope) {
        ScriptableObject o = (ScriptableObject) cx.newObject(scope);
        o.setPrototype(null);
        o.setParentScope(null);
        return o;
    }

    /** 往宿主对象上挂一个函数。 */
    public static void put(ScriptableObject target, Scriptable scope, String name, int arity, Body body) {
        ScriptableObject.putProperty(target, name, of(scope, name, arity, body));
    }
}
