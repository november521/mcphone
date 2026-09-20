package com.november.mcphone.core.script.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 宿主自己实现的 {@code require}（施工方案 §15.2）。Rhino 没有模块系统。
 *
 * <h2>查表，不解析文件路径</h2>
 *
 * §15.2 要求"只接受 {@code ./} 或 {@code ../} 开头的包内相对路径、解析后校验仍在包根内"。
 * <b>落到文件系统上去解析是不必要的风险</b>：zip 里解出来的符号链接能跳出包根、
 * Windows 的 {@code \} 与保留设备名、大小写不敏感造成同一模块两份缓存 —— 三个都要单独挡。
 *
 * <p>而 §11.2 的清单本来就枚举了那 ≤16 个 {@code .js}。所以这里对着<b>内存里的规范名表</b>查，
 * 穿越在结构上就不成立，一行文件系统代码都没有。
 *
 * <h2>两个计数的口径</h2>
 *
 * 写错这两个口径是最容易的：
 * <ul>
 *   <li><b>模块 ≤ 16</b> 数的是<b>表里的条目数</b>，建表时一次判完，不是 {@code require()} 被调的次数</li>
 *   <li><b>深度 ≤ 8</b> 数的是<b>当前 require 栈的深度</b>，不是累计次数</li>
 * </ul>
 * 缓存命中直接返回，<b>既不计深度也不计模块数</b> —— 否则几个模块都引用同一个工具模块时会莫名其妙撞上限。
 */
public final class ScriptModules {

    /** 一个包最多几个 {@code .js}（§11.2）。 */
    public static final int MAX_MODULES = 16;

    /** require 栈最深几层（§15.2）。 */
    public static final int MAX_DEPTH = 8;

    /** 规范名 → 源码。规范名相对包根、{@code /} 分隔、不含 {@code .} 与 {@code ..}。 */
    private final Map<String, String> sources;

    /** 规范名 → 已经求值出来的 {@code exports}。 */
    private final Map<String, Object> cache = new HashMap<>();

    /** 正在加载的链，既是循环依赖检测也是深度计数。 */
    private final Deque<String> loading = new ArrayDeque<>();

    public ScriptModules(Map<String, String> sources) {
        if (sources.size() > MAX_MODULES) {
            throw HostError.quota("包内 .js 有 " + sources.size() + " 个，上限 " + MAX_MODULES);
        }
        for (String name : sources.keySet()) {
            if (!name.endsWith(".js")) {
                throw HostError.invalid("模块表里有非 .js：" + name);
            }
            if (!isCanonicalName(name)) {
                throw HostError.invalid("模块表里有非规范名：" + name);
            }
        }
        this.sources = Map.copyOf(sources);
    }

    /** 解析器：把一个模块的源码求值成 exports。由调用方提供（要用到 scope 与 Context）。 */
    @FunctionalInterface
    public interface Loader {
        Object load(String canonicalName, String source);
    }

    /**
     * {@code require(spec)}。
     *
     * @param spec 脚本写的那一串，必须 {@code ./} 或 {@code ../} 开头
     * @param from 发起 require 的那个模块的规范名，入口是 {@code "server.js"}
     */
    public synchronized Object require(String spec, String from, Loader loader) {
        if (spec == null || (!spec.startsWith("./") && !spec.startsWith("../"))) {
            throw HostError.invalid("require 只许包内相对路径：" + spec);
        }
        String key = normalize(parentOf(from), spec);
        if (key == null || !sources.containsKey(key)) {
            throw HostError.invalid("require 找不到：" + spec);
        }
        // 缓存命中不计深度也不计模块数
        if (cache.containsKey(key)) return cache.get(key);

        if (loading.contains(key)) {
            List<String> chain = new ArrayList<>(loading);
            java.util.Collections.reverse(chain);
            chain.add(key);
            throw HostError.invalid("循环依赖：" + String.join(" -> ", chain));
        }
        if (loading.size() >= MAX_DEPTH) {
            throw HostError.quota("require 深度超过 " + MAX_DEPTH);
        }

        loading.push(key);
        try {
            Object exports = loader.load(key, sources.get(key));
            cache.put(key, exports);
            return exports;
        } finally {
            loading.pop();
        }
    }

    /** 现在有几个模块。 */
    public int size() {
        return sources.size();
    }

    /** 有没有这个规范名。 */
    public boolean has(String canonicalName) {
        return sources.containsKey(canonicalName);
    }

    /** 当前 require 栈多深。只给测试用。 */
    public synchronized int depth() {
        return loading.size();
    }

    private static boolean isCanonicalName(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.endsWith("/")) return false;
        if (name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.contains("//")) return false;
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
            String stem = part.endsWith(".js") ? part.substring(0, part.length() - 3) : part;
            if (stem.isEmpty() || stem.chars().allMatch(c -> c == '.')) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- 纯字符串的规范化

    /** {@code "server/gift.js"} → {@code "server"}；顶层返回空串。 */
    static String parentOf(String module) {
        int i = module.lastIndexOf('/');
        return i < 0 ? "" : module.substring(0, i);
    }

    /**
     * 纯字符串的路径规范化。<b>弹到包根之上一律返回 null</b>（调用方当找不到处理）。
     *
     * <p>不碰文件系统，所以不必管符号链接、{@code \}、保留设备名 —— 它们在这里不成其为路径。
     */
    static String normalize(String base, String spec) {
        List<String> parts = new ArrayList<>();
        if (!base.isEmpty()) {
            for (String p : base.split("/")) if (!p.isEmpty()) parts.add(p);
        }
        for (String p : spec.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (parts.isEmpty()) return null;      // 弹出了包根
                parts.remove(parts.size() - 1);
                continue;
            }
            // 反斜杠、冒号、通配这些在规范名里一个都不该出现
            if (p.indexOf('\\') >= 0 || p.indexOf(':') >= 0) return null;
            parts.add(p);
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }
}
