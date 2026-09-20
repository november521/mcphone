package com.november.mcphone.core.script.sfc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次重排（或一次点击）的求值环境：state、v-for 的循环变量、§9.5.7 的运行期上限与 warn。
 *
 * <p>超限一律降级不抛：表达式抛异常会让整页挂掉，而数据可能来自服务端，长度与缺键在运行期都不可控。
 */
final class EvalContext {

    static final int MAX_DEPTH = 32;
    static final int MAX_EVALS = 4096;
    static final int MAX_CONCAT = 1024;
    /** 同一次重排的 warn 去重后最多留这么多条，每帧重排时日志不至于刷屏。 */
    static final int MAX_WARNINGS = 32;

    private final Map<String, Object> state;
    private final Map<String, Object> host;
    private final String file;
    private final List<String> names = new ArrayList<>();
    private final List<Object> values = new ArrayList<>();
    private final Set<String> warnings = new LinkedHashSet<>();
    private final Set<String> once = new HashSet<>();
    private int evals;
    private boolean exhausted;

    int depth;
    int line;

    EvalContext(Map<String, Object> state, String file) {
        this(state, Map.of(), file);
    }

    EvalContext(Map<String, Object> state, Map<String, Object> host, String file) {
        this.state = state;
        this.host = ExprParser.Host.withDefaults(host);
        this.file = file;
    }

    void push(String name, Object value) {
        names.add(name);
        values.add(value);
    }

    void pop() {
        names.remove(names.size() - 1);
        values.remove(values.size() - 1);
    }

    /** 此刻绑着的 v-for 变量名，外层在前。@click 实例化时拿它捕获，点击时还原。 */
    List<String> boundNames() {
        return List.copyOf(names);
    }

    /** 与 {@link #boundNames()} 一一对应的值。可能有 null（数组里写了 null），所以不用 List.copyOf。 */
    List<Object> boundValues() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * 循环变量先于宿主上下文、宿主上下文先于 state：编译期已经拒了遮蔽（v-for 变量与 state 键都
     * 不许叫 {@code backend}），这里的顺序只是把"宿主给的只读对象不许被 state 顶掉"钉死。
     */
    Object lookup(String name) {
        for (int i = names.size() - 1; i >= 0; i--) {
            if (names.get(i).equals(name)) return values.get(i);
        }
        if (host.containsKey(name)) return host.get(name);
        return state.get(name);
    }

    /** 记一次求值。用完 4096 次后返回 false，只在第一次超出时记 warn。 */
    boolean budget() {
        if (evals >= MAX_EVALS) {
            if (!exhausted) {
                exhausted = true;
                warnAlways("一次重排求值超过 " + MAX_EVALS + " 次，剩下的表达式按 null 处理");
            }
            return false;
        }
        evals++;
        return true;
    }

    boolean exhausted() {
        return exhausted;
    }

    void warn(String reason) {
        if (warnings.size() < MAX_WARNINGS) warnings.add(file + ":" + line + " " + reason);
    }

    /** 截断、求值预算用完这类「页面少了一截」的 warn 不受条数上限挡：前面刷满 32 条后它们会悄悄消失。 */
    void warnAlways(String reason) {
        warnings.add(file + ":" + line + " " + reason);
    }

    /** 同一类「页面少了一截」的 warn 一次实例化只记第一处：很多个 v-for 各自超出上限时不刷满日志。 */
    void warnOnce(String kind, String reason) {
        if (once.add(kind)) warnAlways(reason);
    }

    List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** 拼接结果超长时截断并记 warn。 */
    String capped(String s) {
        if (s.length() <= MAX_CONCAT) return s;
        warn("拼出来的字符串 " + s.length() + " 字，截到 " + MAX_CONCAT);
        return Values.cut(s, MAX_CONCAT);
    }
}
