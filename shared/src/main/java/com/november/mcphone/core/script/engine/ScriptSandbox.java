package com.november.mcphone.core.script.engine;

import org.mozilla.javascript.Callable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.List;
import java.util.Set;

/**
 * 脚本沙箱（施工方案 §16.3）。<b>这一份与 §16.3 的配方有四处不同，每一处都是实测出来的。</b>
 *
 * <h2>偏离一：全局改成白名单，不再点名删</h2>
 *
 * §16.3 的 {@code BANNED} 点名删六个。实测照抄之后：
 *
 * <pre>
 * typeof Script          → function
 * new Script('40+2')()   → 42          ← 第三个运行时编译入口还在
 * typeof Int8Array       → function
 * Map.prototype.zz = 1   → 污染成功（SEAL 只有 10 个名字，Map 不在）
 * </pre>
 *
 * {@code Script} 拿不到 Java（沙箱没破），但它<b>击穿 §16.3 自己写的那条理由</b> ——
 * "运行的代码可以和 OP 审的源码不是一回事，静态审查全部失效"。§14 的服主审批流建立在
 * "服务端跑的就是审过的那份 server.js"上。
 *
 * <p>点名删的名单每次 Rhino 升版都会漏一批，所以反过来：<b>只留 {@link #ALLOWED_GLOBALS}，其余全删</b>。
 * {@code Proxy} / {@code Reflect} 实测本来就不存在，{@code BANNED} 里那两条是空转。
 *
 * <h2>偏离二：密封也按枚举，不点名十个</h2>
 *
 * 留下来的每一个全局，只要是 {@code ScriptableObject}，连同它的 {@code prototype} 一起密封。
 *
 * <h2>偏离三：放大型内置按枚举包一层，不点名五个</h2>
 *
 * §16.4 点名 {@code repeat/padStart/padEnd/fill/join}。那五个挡不住 {@code JSON.stringify}
 * 与下一版新增的方法，而 {@code String.prototype} 上有 50 个方法、{@code Array.prototype} 上有 40 个。
 * 所以整个原型上的函数都包一层尺寸闸（在密封之前）。
 *
 * <p>包的是 {@link LambdaFunction}，<b>不是 {@code FunctionObject}</b> —— 后者会把宿主的
 * RuntimeException 转成脚本 catch 得到的 InternalError。而且包完要把函数自己也密封：
 * {@code LambdaFunction} 会带一个未密封的 {@code .prototype}，那是跨调用的驻留点。
 *
 * <p><b>脚本从包装函数身上摸不回 Java</b>（实测四种写法逐条爬过）：{@code .constructor}、
 * {@code .getClass}、{@code .call/.apply/.bind}、{@code Object.getPrototypeOf} 全是 undefined 或拒绝。
 * 关键是下面 ① 那一步把 {@code .constructor} 链掐在 {@code Object} 上了。
 *
 * <h2>偏离四：ClassShutter 在 ContextFactory 上设一次，不在这里每次设</h2>
 *
 * {@code Context} 是<b>线程绑定</b>的：同一线程第二次 {@code enterContext} 拿到的是同一个
 * Context，而它已经有 shutter 了，再设直接 {@code SecurityException}。见 {@link ScriptBudget}。
 *
 * <h2>ctx 这类宿主对象怎么注入</h2>
 *
 * <b>{@code setClassShutter(n -> false)} 里的 false 是无条件的。</b>
 * 不许改成放行自己的包 —— 实测那样脚本能
 * {@code ctx.player.getClass().getClassLoader()} 拿到 AppClassLoader，沙箱当场报废。
 * {@code ctx} 与它下面的每一级都必须是手工建的对象：{@code setPrototype(null)} +
 * {@code setParentScope(null)} + {@code sealObject()}，值只许是 JS 原语、手工建的对象、
 * 或 {@link LambdaFunction}。<b>任何宿主方法不许把 Java 对象直接返回给脚本。</b>
 */
public final class ScriptSandbox {

    private ScriptSandbox() {
    }

    /**
     * 脚本能看见的全局，<b>只有这些</b>。
     *
     * <p>删掉的里面值得点名的几个：{@code Script}（运行时编译）、
     * {@code ArrayBuffer} / {@code DataView} / 各 TypedArray（19 毫秒 200 MB，观察器零回调）、
     * {@code Promise}（§16.2 实测没有 await 也没有事件循环，留着只会让作者写出"看着像异步其实是同步"的代码）、
     * {@code escape} / {@code unescape} / {@code Continuation} / {@code JavaException} / {@code With}。
     * §32.7 那七个样例 App 一个都用不上它们。
     */
    public static final Set<String> ALLOWED_GLOBALS = Set.of(
            "Object", "Array", "String", "Number", "Boolean", "BigInt",
            "Math", "JSON", "Date", "RegExp",
            "Map", "Set", "WeakMap", "WeakSet", "Symbol", "globalThis",
            "Error", "EvalError", "RangeError", "ReferenceError", "SyntaxError",
            "TypeError", "URIError", "InternalError", "AggregateError",
            "NaN", "Infinity", "undefined", "isNaN", "isFinite", "parseInt", "parseFloat",
            "decodeURI", "decodeURIComponent", "encodeURI", "encodeURIComponent");

    /** 这几个的 this 小、参数大也能炸，单独包。 */
    private static final List<String> EXTRA_WRAPPED_HOLDERS = List.of("Array", "JSON");

    /**
     * 建一个加固过的顶层 scope。<b>三步顺序不能变。</b>
     *
     * <p>{@code cx} 必须来自 {@link ScriptBudget}（那里已经设好 optimizationLevel、语言版本与 shutter）。
     */
    public static ScriptableObject harden(Context cx) {
        ScriptableObject s = (ScriptableObject) cx.initSafeStandardObjects(null, false);

        // ① 切断 .constructor 链 —— 光删全局不够，这一步是关键。
        //    少了它，[].constructor.constructor('return 42')() 照样能编译执行
        Object fn = ScriptableObject.getProperty(s, "Function");
        if (fn instanceof Scriptable fnObj) {
            Object proto = ScriptableObject.getProperty(fnObj, "prototype");
            if (proto instanceof Scriptable p) ScriptableObject.deleteProperty(p, "constructor");
        }

        // ② 白名单：不在名单里的全局一律删。
        //    必须 getAllIds() 不是 getIds()：标准全局都是 DONTENUM 的，getIds() 一个都看不见，
        //    照它写等于这一步整个空转（实测：写成 getIds() 时 eval / Script / ArrayBuffer 全都还在）
        for (Object id : s.getAllIds()) {
            if (id instanceof String name && !ALLOWED_GLOBALS.contains(name)) {
                ScriptableObject.deleteProperty(s, name);
            }
        }

        // ②.5 放大型内置包一层尺寸闸 —— 必须在密封【之前】，密封之后就换不动了
        wrapPrototype(s, "String");
        wrapPrototype(s, "Array");
        for (String holder : EXTRA_WRAPPED_HOLDERS) wrapOwn(s, holder);

        // ③ 逐个密封留下来的全局与它的 prototype。
        //    【不要】密封顶层 scope —— 实测那样脚本连 var 都声明不了
        for (Object id : s.getAllIds()) {
            if (!(id instanceof String name)) continue;
            Object o = ScriptableObject.getProperty(s, name);
            // globalThis 指的就是顶层 scope 自己。封了它脚本连 var 都声明不了
            // （§16.3 的"不要密封顶层 scope"就是这一条，而白名单里放了 globalThis 就会踩到）
            if (o == s) continue;
            if (o instanceof ScriptableObject so) {
                Object p = ScriptableObject.getProperty(so, "prototype");
                if (p instanceof ScriptableObject po) po.sealObject();
                so.sealObject();
            }
        }
        return s;
    }

    /** 把 {@code <name>.prototype} 上的每个函数换成带尺寸闸的版本。 */
    private static void wrapPrototype(Scriptable scope, String name) {
        Object holder = ScriptableObject.getProperty(scope, name);
        if (!(holder instanceof Scriptable h)) return;
        Object proto = ScriptableObject.getProperty(h, "prototype");
        if (proto instanceof ScriptableObject po) wrapAll(po, scope, name + ".prototype");
    }

    /** 把 {@code <name>} 自己身上的每个函数换成带尺寸闸的版本（JSON.stringify / parse）。 */
    private static void wrapOwn(Scriptable scope, String name) {
        Object holder = ScriptableObject.getProperty(scope, name);
        if (holder instanceof ScriptableObject ho) wrapAll(ho, scope, name);
    }

    private static void wrapAll(ScriptableObject target, Scriptable scope, String where) {
        for (Object id : target.getAllIds()) {
            if (!(id instanceof String name)) continue;
            Object v = ScriptableObject.getProperty(target, name);
            if (!(v instanceof Callable inner)) continue;
            LambdaFunction wrapped = new LambdaFunction(scope, name, 0, (cx, sc, thisObj, args) -> {
                String boundary = where + "." + name;
                HostFn.enter(boundary);
                try {
                    // Array.from uses its receiver as an optional result constructor. Letting scripts
                    // substitute one creates a preflight/call TOCTOU window: that constructor can grow
                    // the source after it was measured but before Rhino starts consuming its iterator.
                    if ("Array".equals(where) && "from".equals(name) && thisObj != target) {
                        throw HostError.invalid(boundary + ": 必须直接通过内置 Array 调用");
                    }
                    SizeGate.checkNativeCall(where, name, thisObj, args);
                    Object out = inner.call(cx, sc, thisObj, args);
                    SizeGate.check(out, boundary + " 的返回值");
                    return out;
                } finally {
                    HostFn.exit();
                }
            });
            // LambdaFunction 自带一个未密封的 .prototype，那是跨调用的驻留点
            wrapped.sealObject();
            ScriptableObject.putProperty(target, name, wrapped);
        }
    }
}
