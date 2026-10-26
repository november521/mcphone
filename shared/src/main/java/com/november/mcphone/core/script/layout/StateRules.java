package com.november.mcphone.core.script.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * state 的键名与值规则（施工方案 §9.7）。IR 的 JSON 形态、.vue 的 {@code <script>} 与 {@code @click} 写入都过这一份：抄几份必然分叉。
 *
 * <p>值的 Java 形态只有 Integer / Boolean / String / List / Map。
 */
public final class StateRules {

    public static final int MAX_KEYS = 16;
    public static final int MAX_STRING = 64;
    /** SFC page strings; legacy IR and single-line inputs retain MAX_STRING. Units are UTF-16. */
    public static final int MAX_PAGE_STRING = 4096;
    public static final int MAX_PAGE_TEXT = 16 * 1024;
    public static final int MAX_ARRAY = 32;
    public static final int MAX_OBJECT_KEYS = 16;
    /** 数组套对象是 2 层，再往里就拒。 */
    public static final int MAX_DEPTH = 2;

    public static final Pattern KEY = Pattern.compile("[a-z][a-zA-Z0-9_]{0,31}");
    public static final String KEY_RULE = "匹配 [a-z][a-zA-Z0-9_]{0,31}";

    private StateRules() {
    }

    /** 值的种类：int / bool / string / array / object，别的返回 null。报错文案与类型比较都用它。 */
    public static String kind(Object value) {
        if (value instanceof Integer) return "int";
        if (value instanceof Boolean) return "bool";
        if (value instanceof String) return "string";
        if (value instanceof List<?>) return "array";
        if (value instanceof Map<?, ?>) return "object";
        return null;
    }

    /** 作者写的值（初值、@click 写入、IR 的 set）不合规时返回理由，合规返回 null。 */
    public static String check(Object value) {
        return check(value, MAX_STRING);
    }

    public static String check(Object value, int stringLimit) { return check(value, 0, true, stringLimit); }

    /** Count all string leaves, including arrays/objects; callers validate shape before counting. */
    public static long textUnits(Object value) {
        if (value instanceof String s) return s.length();
        if (value instanceof List<?> list) return list.stream().mapToLong(StateRules::textUnits).sum();
        if (value instanceof Map<?, ?> map) return map.values().stream().mapToLong(StateRules::textUnits).sum();
        return 0;
    }

    /**
     * 只查形状：种类、同构、层数、对象一层、键名，不查个数与长度。宿主写进来的服务端数据用它：
     * 32 项 / 64 字是给作者手写的，服务端的列表本来就可能更长，§9.4.5 的 2048 截断就是为它留的。
     */
    public static String checkShape(Object value) {
        return check(value, 0, false, MAX_STRING);
    }

    private static String check(Object value, int depth, boolean limits, int stringLimit) {
        String kind = kind(value);
        if (kind == null) return "只能是 int / bool / string / array / object，给的是 " + (value == null ? "null" : "别的");
        switch (kind) {
            case "string" -> {
                int len = ((String) value).length();
                return limits && len > stringLimit ? "字符串 " + len + " 字，最多 " + stringLimit + " 字" : null;
            }
            case "array" -> {
                if (depth >= MAX_DEPTH) return "嵌套超过 " + MAX_DEPTH + " 层";
                List<?> list = (List<?>) value;
                if (limits && list.size() > MAX_ARRAY) return "数组 " + list.size() + " 项，最多 " + MAX_ARRAY + " 项";
                Object ref = null;
                for (Object e : list) {
                    String why = check(e, depth + 1, limits, stringLimit);
                    if (why != null) return why;
                    // 同构：模板里 v-for 出来的每一项要能用同一套写法读，对象要同一组键。空数组不当基准，否则 [[], [1], ['x']] 能过
                    if (ref != null && !sameShape(ref, e)) return "数组元素要同一种形状，混了 " + shape(ref) + " 和 " + shape(e);
                    if (ref == null || ref instanceof List<?> r && r.isEmpty()) ref = e;
                }
                return null;
            }
            case "object" -> {
                if (depth >= MAX_DEPTH) return "嵌套超过 " + MAX_DEPTH + " 层";
                Map<?, ?> map = (Map<?, ?>) value;
                if (limits && map.size() > MAX_OBJECT_KEYS) {
                    return "对象 " + map.size() + " 个键，最多 " + MAX_OBJECT_KEYS + " 个";
                }
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!(e.getKey() instanceof String k) || !KEY.matcher(k).matches()) {
                        return "对象的键 '" + e.getKey() + "' 要" + KEY_RULE;
                    }
                    Object v = e.getValue();
                    if (v instanceof List<?> || v instanceof Map<?, ?>) return "对象只能一层，'" + k + "' 的值不能再是 array / object";
                    String why = check(v, depth + 1, limits, stringLimit);
                    if (why != null) return why;
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * value 能不能写进初值是 declared 的那个 key：种类相同；数组的元素形状与初值的元素一致（初值是空数组时不限）；
     * 对象的键与各键种类一致。编译期按初值推断 v-for 变量的类型，靠这条成立。合规返回 null。
     */
    public static String fits(Object declared, Object value) {
        if (!Objects.equals(kind(declared), kind(value))) {
            return "是 " + kind(declared) + "，不能写入 " + (value == null ? "null" : Objects.requireNonNullElse(kind(value), "别的"));
        }
        if (declared instanceof List<?> d && value instanceof List<?> v) {
            Object de = representative(d);
            Object ve = representative(v);
            if (de != null && ve != null && !sameShape(de, ve)) return "的元素要是 " + shape(de) + "，给的是 " + shape(ve);
        } else if (declared instanceof Map<?, ?> && !sameShape(declared, value)) {
            return "要是 " + shape(declared) + "，给的是 " + shape(value);
        }
        return null;
    }

    /** 数组里拿来代表元素形状的那一项：优先非空的子数组。空数组返回 null。 */
    public static Object representative(List<?> list) {
        for (Object e : list) {
            if (!(e instanceof List<?> l && l.isEmpty())) return e;
        }
        return list.isEmpty() ? null : list.get(0);
    }

    /** 同种类；对象的键与各键的种类相同；数组套数组时比两边的代表项（各自内部已经同构）。空数组与任何数组同形。 */
    private static boolean sameShape(Object a, Object b) {
        if (!Objects.equals(kind(a), kind(b))) return false;
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (!ma.keySet().equals(mb.keySet())) return false;
            for (Object k : ma.keySet()) {
                if (!Objects.equals(kind(ma.get(k)), kind(mb.get(k)))) return false;
            }
            return true;
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            Object ra = representative(la);
            Object rb = representative(lb);
            return ra == null || rb == null || sameShape(ra, rb);
        }
        return true;
    }

    private static String shape(Object v) {
        if (v instanceof Map<?, ?> m) return "object" + new TreeSet<>(m.keySet().stream().map(String::valueOf).toList());
        if (v instanceof List<?> l) {
            Object r = representative(l);
            return r == null ? "array" : "array<" + shape(r) + ">";
        }
        return String.valueOf(kind(v));
    }

    /** 只读的深拷贝。state 里的数组交给模板读，被外面改了会让两次重排之间悄悄变样。 */
    public static Object freeze(Object value) {
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object e : list) out.add(freeze(e));
            return Collections.unmodifiableList(out);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), freeze(e.getValue()));
            return Collections.unmodifiableMap(out);
        }
        return value;
    }
}
