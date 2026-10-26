package com.november.mcphone.core.script.layout;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一页的 UI 状态（施工方案 §9.7）：key 只有初值里声明过的那些，值是 int / bool / string / array / object，形状不变。不持久化。
 *
 * <p>{@link #revision()} 只在值真的变了时加一，页面拿它判断要不要重排（§7.6）。
 */
public final class UiState {

    private final Map<String, Object> values;
    /** 初值。写入对着它比形状，不对着当前值比：对着当前值比的话，先写 [] 再写 ['a'] 就把 int 数组换成了字符串数组。 */
    private final Map<String, Object> declared;
    private int revision;
    private final int stringLimit;
    private final int textBudget;

    private UiState(Map<String, Object> values, Map<String, Object> declared, int stringLimit, int textBudget) {
        this.values = values;
        this.declared = declared;
        this.stringLimit = stringLimit;
        this.textBudget = textBudget;
    }

    /** 以初值建一份，例如 {@code NodeParser.Ui#state()}。初值按 §9.7 的全部规则查，不合规抛 {@link IllegalArgumentException}。 */
    public static UiState of(Map<String, Object> initial) {
        return create(initial, StateRules.MAX_STRING, 0);
    }

    /** Frontend SFC pages have bounded long strings; shared IR validation keeps its original limits. */
    public static UiState page(Map<String, Object> initial) {
        return create(initial, StateRules.MAX_PAGE_STRING, StateRules.MAX_PAGE_TEXT);
    }

    private static UiState create(Map<String, Object> initial, int stringLimit, int textBudget) {
        if (initial.size() > StateRules.MAX_KEYS) {
            throw new IllegalArgumentException("state 最多 " + StateRules.MAX_KEYS + " 个 key，给了 " + initial.size() + " 个");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : initial.entrySet()) {
            String key = Objects.requireNonNull(e.getKey(), "state 的 key");
            if (!StateRules.KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("state 的 key '" + key + "' 要" + StateRules.KEY_RULE);
            }
            String why = StateRules.check(e.getValue(), stringLimit);
            if (why != null) throw new IllegalArgumentException("state['" + key + "'] 的初值不合规：" + why);
            copy.put(key, StateRules.freeze(e.getValue()));
        }
        if (textBudget > 0 && StateRules.textUnits(copy) > textBudget) throw new IllegalArgumentException("页面 state 文本总量最多 " + textBudget + " 个 UTF-16 单元");
        return new UiState(copy, Collections.unmodifiableMap(new LinkedHashMap<>(copy)), stringLimit, textBudget);
    }

    public static UiState empty() {
        return of(Map.of());
    }

    /** 同一份声明、当前值的副本，revision 从 0 起。点击先在副本上试，全部成功才写回原件。 */
    public UiState copy() {
        return new UiState(new LinkedHashMap<>(values), declared, stringLimit, textBudget);
    }

    public int stringLimit() { return stringLimit; }

    /** 全部键值，只读。模板编译拿它知道声明了哪些 key、各是什么种类。 */
    public Map<String, Object> values() {
        return Collections.unmodifiableMap(values);
    }

    public int revision() {
        return revision;
    }

    /** 没有这个 key 时返回 null：P1 起数据可能来自服务端，缺键是常态（§9.5.5）。 */
    public Object get(String key) {
        return values.get(key);
    }

    public int getInt(String key) {
        return values.get(key) instanceof Integer i ? i : 0;
    }

    public boolean getBool(String key) {
        return values.get(key) instanceof Boolean b && b;
    }

    public String getString(String key) {
        return values.get(key) instanceof String s ? s : "";
    }

    /** 作者写入（@click）的判据：key 声明过、形状与初值一致、值过 §9.7 的全部规则（个数与长度也查）。合规返回 null。 */
    public String writeProblem(String key, Object value) {
        Object d = declared.get(key);
        if (d == null) return "state 里没有 '" + key + "'";
        String why = StateRules.fits(d, value);
        if (why != null) return "state['" + key + "'] " + why;
        why = StateRules.check(value, stringLimit);
        return why == null ? null : "state['" + key + "'] 不能写入：" + why;
    }

    /** Validate the final group, so several individually valid writes cannot exceed the page budget. */
    public String writeProblems(Map<String, Object> next) {
        if (textBudget == 0) return null; // Legacy statements permit trusted host values outside author limits.
        if (!next.keySet().equals(declared.keySet())) return "不能添加或删除 state 字段";
        for (var entry : next.entrySet()) {
            String why = writeProblem(entry.getKey(), entry.getValue());
            if (why != null) return why;
        }
        return textBudget > 0 && StateRules.textUnits(next) > textBudget
                ? "页面 state 文本总量最多 " + textBudget + " 个 UTF-16 单元" : null;
    }

    /**
     * 改一个值。key 必须声明过、形状必须与初值一致，否则抛 {@link IllegalArgumentException}。
     * 个数与长度不在这里查：宿主写进来的服务端数据可以比作者手写的长（§9.4.5 的 2048 截断为它而设）；作者的写入另过 {@link #writeProblem}。
     */
    public void set(String key, Object value) {
        Object d = declared.get(key);
        if (d == null) throw new IllegalArgumentException("state 里没有 '" + key + "'");
        String why = StateRules.fits(d, value);
        if (why == null) why = StateRules.checkShape(value);
        if (why != null) throw new IllegalArgumentException("state['" + key + "'] " + why);
        if (!values.get(key).equals(value)) {
            values.put(key, StateRules.freeze(value));
            revision++;
        }
    }

    /** bool 取反（§4.5 的 toggle）。 */
    public void toggle(String key) {
        set(key, !getBool(key));
    }

    /** showIf / enabledIf 是否成立（§4.4）。truthy 为 true 时值为真才成立，为 false 时值为假才成立。 */
    public boolean test(Node.ShowIf condition) {
        Object v = values.get(condition.key());
        return switch (condition.kind()) {
            case EQ -> Objects.equals(v, condition.value());
            case NE -> !Objects.equals(v, condition.value());
            case TRUTHY -> truthy(v) == Boolean.TRUE.equals(condition.value());
        };
    }

    /** int 非 0、bool true、非空字符串、任何数组与对象（空的也算，§9.5.4）算真。 */
    static boolean truthy(Object v) {
        if (v instanceof Integer i) return i != 0;
        if (v instanceof Boolean b) return b;
        if (v instanceof List<?> || v instanceof Map<?, ?>) return true;
        return v instanceof String s && !s.isEmpty();
    }
}
