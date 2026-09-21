package com.november.mcphone.core.script.engine;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.Map;
import java.util.Set;

/**
 * 一个 App 的后端 scope（施工方案 §16.3"每个 App 一个独立 scope"、§15.2 的 {@code actions} 表）。
 *
 * <h2>为什么每次调用还要再建一个子 scope</h2>
 *
 * {@code actions} 表必须跨调用活着，所以 App 的 scope 是复用的。而 §16.4 的两道预算都是
 * <b>每次调用</b>的，于是跨调用累积整个绕过了预算。实测：脚本 {@code g = g + g;}，
 * 每次调用只有个位数条指令、一次都没超预算，<b>第 26 次调用 OutOfMemoryError</b>。
 *
 * <p>两条一起堵：
 * <ol>
 *   <li>每次调用在一个<b>子 scope</b> 上跑，{@code var} 声明落在子 scope，调用结束即丢</li>
 *   <li>调用结束扫一眼 App scope 顶层的驻留量，超 {@link #MAX_RETAINED_CHARS} 就重建 scope 并记审计</li>
 * </ol>
 *
 * <p>扫驻留量是便宜的：{@code CharSequence.length()} 对 {@code ConsString} 是 O(1)，
 * 不会因为量尺寸把 rope 物化。建一次 App scope 实测 0.76 毫秒。
 */
public final class AppScope {

    /** 一个 App 的 scope 顶层最多留多少字符。超了就重建。 */
    public static final long MAX_RETAINED_CHARS = 1 << 20;

    /** 入口模块的规范名（施工方案 §15.2）：它定义 {@code actions} 表。 */
    public static final String ENTRY = "server.js";

    /** 扫驻留量时最多看几个属性，防着一个有几万个键的 scope 把扫描本身变成负担。 */
    public static final int MAX_SCANNED = 4096;

    private final String appId;
    private final ScriptBudget budget;
    private final ScriptModules modules;
    /**
     * 这个 App 已批准的能力集（S18）：来自 {@code Deployment.approvedCapabilities}，
     * <b>装配期冻结</b>（worker 不许读部署表）。重新批准走 {@code reassemble} 换 scope。
     */
    private final java.util.Set<String> capabilities;

    private volatile ScriptableObject scope;

    /** Parent and action table captured from the same initialized App scope. */
    public record Invocation(Scriptable callScope, Scriptable actions) {
    }

    /** 没有能力集的旧写法（断言用）。 */
    public AppScope(String appId, ScriptBudget budget, Map<String, String> jsSources) {
        this(appId, budget, jsSources, java.util.Set.of());
    }

    public AppScope(String appId, ScriptBudget budget, Map<String, String> jsSources,
                    java.util.Set<String> capabilities) {
        this.appId = appId;
        this.budget = budget;
        this.modules = new ScriptModules(jsSources);
        this.capabilities = capabilities == null ? java.util.Set.of() : Set.copyOf(capabilities);
    }

    public String appId() {
        return appId;
    }

    /** 已批准能力集（冻结快照）。判定用 {@code CapabilityPolicy}。 */
    public java.util.Set<String> capabilities() {
        return capabilities;
    }

    public ScriptModules modules() {
        return modules;
    }

    /**
     * 这个 App 的顶层 scope。第一次用时建：加固 → 装 {@code require} → 在同一个 scope 里求值入口
     * {@link #ENTRY} 定义出 {@code actions} 表。<b>只在持有 Context 的线程上调。</b>
     *
     * <p>求值发生在调用方的预算之内（{@code RhinoEvaluator} 先 {@code budget.begin()} 再进来），
     * 所以一份写坏/写恶意的 {@code server.js} 烧不掉服务器。失败时<b>不发布</b>半成品 scope ——
     * 下次调用重建重试，并把错误照常报上来。
     */
    public synchronized ScriptableObject scope(Context cx) {
        if (scope == null) {
            ScriptableObject s = ScriptSandbox.harden(cx, target -> installRequire(cx, target));
            String entry = modules.source(ENTRY);
            if (entry != null) {
                cx.evaluateString(s, entry, ENTRY, 1, null);
            }
            scope = s;
        }
        return scope;
    }

    /** 把 {@code require(spec)} 装到加固后的顶层 scope 上；解析与缓存都在 {@link ScriptModules} 里。 */
    private void installRequire(Context cx, ScriptableObject target) {
        HostFn.put(target, target, "require", 1, (c, s, a) -> modules.require(
                HostFn.str(a, 0, "require"), modules.currentModule(),
                (name, source) -> c.evaluateString(s, source, name, 1, null)));
        // 顶层 scope 刻意不密封（否则脚本连 var 都声明不了），于是 require 这个绑定本身是可写的：
        // 脚本能把自己这个 App 的模块加载弄坏（不是提权 —— 它换不成宿主函数）。定成只读，与其它宿主全局同待遇。
        Object fn = ScriptableObject.getProperty(target, "require");
        if (fn != null) {
            ScriptableObject.defineProperty(target, "require", fn,
                    ScriptableObject.READONLY | ScriptableObject.PERMANENT | ScriptableObject.DONTENUM);
        }
    }

    /**
     * Atomically captures the parent and actions table used by one invocation. This prevents a
     * concurrent first use or retained-scope reset from pairing a child of scope A with actions
     * from scope B.
     */
    public synchronized Invocation beginInvocation(Context cx) {
        ScriptableObject parent = scope(cx);
        Scriptable call = cx.newObject(parent);
        call.setPrototype(parent);
        call.setParentScope(null);
        Object value = ScriptableObject.getProperty(parent, "actions");
        return new Invocation(call, value instanceof Scriptable actions ? actions : null);
    }

    /**
     * 一次调用用的子 scope。
     *
     * <p>{@code setParentScope(null)}：顶层赋值会打到已经密封的 App scope 上并报错，
     * 而不是<b>悄悄</b>驻留一份。
     */
    public synchronized Scriptable callScope(Context cx) {
        ScriptableObject parent = scope(cx);
        Scriptable call = cx.newObject(parent);
        call.setPrototype(parent);
        call.setParentScope(null);
        return call;
    }

    /** {@code actions} 表。没有就返回 null。 */
    public synchronized Scriptable actions(Context cx) {
        Object a = ScriptableObject.getProperty(scope(cx), "actions");
        return a instanceof Scriptable s ? s : null;
    }

    /**
     * 调用结束扫一遍驻留量。超限就把 scope 丢掉重建，下次调用重新装载。
     *
     * @return 超没超。超了调用方要记一条审计
     */
    public synchronized boolean sweepRetained() {
        if (scope == null) return false;
        long chars = 0;
        int seen = 0;
        for (Object id : scope.getIds()) {          // 只看脚本自己声明的（可枚举的那些）
            if (++seen > MAX_SCANNED) break;
            if (!(id instanceof String name)) continue;
            Object v = readTopLevel(scope, name);
            if (v instanceof CharSequence cs) chars += cs.length();   // ConsString 上是 O(1)
        }
        if (chars <= MAX_RETAINED_CHARS) return false;
        scope = null;                                // 下次 scope(cx) 会重建
        return true;
    }

    private static Object readTopLevel(ScriptableObject scope, String name) {
        Object accessor = scope.getGetterOrSetter(name, 0, false);
        // A retention audit must be observational. Calling script getters here both mutates state
        // and gives package code a second execution path. Accessor-backed values are skipped.
        if (accessor instanceof org.mozilla.javascript.Callable) return org.mozilla.javascript.Undefined.instance;
        return ScriptableObject.getProperty(scope, name);
    }

    /** 服务器停止或 App 卸载时叫。 */
    public synchronized void discard() {
        scope = null;
    }

    public ScriptBudget budget() {
        return budget;
    }
}
