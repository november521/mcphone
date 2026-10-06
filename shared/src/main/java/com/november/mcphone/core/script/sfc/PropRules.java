package com.november.mcphone.core.script.sfc;

import com.november.mcphone.core.script.layout.NodeParser;
import com.november.mcphone.core.script.layout.NodeType;
import com.november.mcphone.core.script.pkg.PackageError;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模板属性 → Node props（施工方案 §5.4 的专有字段、§9.4.2 的静态与绑定）。
 *
 * <p>取值范围与 {@link NodeParser} 的逐字段判据一致，SfcCompilerTest 拿边界值两边对打。静态值编译期不合规就拒；
 * 绑定值在实例化时才知道，不合规时夹紧或丢掉并记 warn，不抛：数据可能来自服务端。
 */
final class PropRules {

    enum Kind { INT, BOOL, TEXT, STRING, ICON, IMAGE_SRC, BIND_INT, BIND_BOOL, BIND_STRING, TABS, ARGS }

    /** 一个属性。staticOnly 的只能写字面值（bind 是 key 名），boundOnly 的只能写 :attr（数组）。 */
    record Spec(String name, Kind kind, int min, int max) {
        boolean staticOnly() {
            return kind == Kind.BIND_INT || kind == Kind.BIND_BOOL || kind == Kind.BIND_STRING;
        }

        boolean boundOnly() {
            return kind == Kind.TABS || kind == Kind.ARGS;
        }

        /** 接受数字或布尔的属性：静态值恰好是 state 的 key 名时多半是忘了冒号（§9.4.2）。 */
        boolean numberOrBool() {
            return kind == Kind.INT || kind == Kind.BOOL;
        }

        String allowed() {
            return switch (kind) {
                case INT -> max == Integer.MAX_VALUE ? "≥ " + min + " 的整数" : min + "–" + max + " 的整数";
                case BOOL -> "true / false";
                case TEXT -> "最多 " + NodeParser.MAX_TEXT + " 字，除换行外没有控制字符";
                case STRING -> "字符串";
                case ICON -> String.join(" ", Names.sorted(NodeParser.ICON_NAMES));
                case IMAGE_SRC -> "包内相对路径，以 .png 结尾";
                case BIND_INT -> "state 里一个 int 的 key 名";
                case BIND_BOOL -> "state 里一个 bool 的 key 名";
                case BIND_STRING -> "state 里一个 string 的 key 名";
                case TABS -> ":tabs=\"[{text:'…'}, …]\"，2–4 项，每项恰好一个 text / i18n / icon";
                case ARGS -> ":args=\"[…]\"，最多 " + NodeParser.MAX_ARGS + " 个 string 或 int";
            };
        }
    }

    /** 值不合规。allowed 是报给作者看的允许取值。 */
    static final class Bad extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Bad(String allowed) {
            super(allowed, null, false, false);
        }
    }

    private static final Pattern PLAIN_INT = Pattern.compile("-?\\d{1,10}");
    private static final Set<String> TAB_FIELDS = Set.of("text", "i18n", "icon");

    private PropRules() {
    }

    /** 这个类型在模板里接受的专有属性，按 §5.4 的顺序。 */
    static Map<String, Spec> of(NodeType type) {
        Map<String, Spec> out = new LinkedHashMap<>();
        switch (type) {
            case GRID -> put(out, "cols", Kind.INT, 1, 6);
            case LIST -> put(out, "item-height", Kind.INT, 0, Integer.MAX_VALUE);
            case SPACER -> put(out, "size", Kind.INT, 0, Integer.MAX_VALUE);
            case DIVIDER -> put(out, "vertical", Kind.BOOL, 0, 0);
            case TEXT -> {
                put(out, "text", Kind.TEXT, 0, 0);
                put(out, "i18n", Kind.STRING, 0, 0);
                put(out, "args", Kind.ARGS, 0, 0);
            }
            case IMAGE -> {
                put(out, "src", Kind.IMAGE_SRC, 0, 0);
                put(out, "w", Kind.INT, 1, 120);
                put(out, "h", Kind.INT, 1, 176);
            }
            case ICON -> {
                put(out, "name", Kind.ICON, 0, 0);
                put(out, "size", Kind.INT, 6, 20);
            }
            case ITEM -> {
                put(out, "item", Kind.STRING, 0, 0);
                put(out, "count", Kind.INT, 1, 99);
                put(out, "size", Kind.INT, 8, 20);
            }
            case BADGE -> put(out, "count", Kind.INT, 0, Integer.MAX_VALUE);
            case PROGRESS -> {
                put(out, "value", Kind.INT, 0, 100);
                put(out, "height", Kind.INT, 2, 12);
            }
            case BUTTON -> {
                put(out, "text", Kind.TEXT, 0, 0);
                put(out, "i18n", Kind.STRING, 0, 0);
                put(out, "args", Kind.ARGS, 0, 0);
                put(out, "enabled", Kind.BOOL, 0, 0);
            }
            case TOGGLE -> {
                put(out, "bind", Kind.BIND_BOOL, 0, 0);
                put(out, "label", Kind.TEXT, 0, 0);
                put(out, "i18n", Kind.STRING, 0, 0);
                put(out, "enabled", Kind.BOOL, 0, 0);
            }
            case TAB_BAR -> {
                put(out, "bind", Kind.BIND_INT, 0, 0);
                put(out, "tabs", Kind.TABS, 0, 0);
            }
            case TEXT_INPUT -> {
                put(out, "bind", Kind.BIND_STRING, 0, 0);
                put(out, "placeholder", Kind.TEXT, 0, 0);
                put(out, "max-length", Kind.INT, 1, 4096);
                put(out, "enabled", Kind.BOOL, 0, 0);
            }
            default -> {
                // box / column / row / stack / scroll 没有专有属性
            }
        }
        return out;
    }

    private static void put(Map<String, Spec> out, String name, Kind kind, int min, int max) {
        out.put(name, new Spec(name, kind, min, max));
    }

    /** 必填的属性组：组里至少写一个。text 的内容、button 的子元素算不算写了由编译器判。 */
    static List<List<String>> required(NodeType type) {
        return switch (type) {
            case TEXT, BUTTON -> List.of(List.of("text", "i18n"));
            case IMAGE -> List.of(List.of("src"));
            case ICON -> List.of(List.of("name"));
            case ITEM -> List.of(List.of("item"));
            case TOGGLE, TEXT_INPUT -> List.of(List.of("bind"));
            case TAB_BAR -> List.of(List.of("bind"), List.of("tabs"));
            default -> List.of();
        };
    }

    // ============================================================
    //  静态值：编译期
    // ============================================================

    /** 写成 attr="raw" 的值。raw 为 null 表示只写了属性名。bind 的 key 是否存在由编译器对着 state 判。 */
    static Object parseStatic(Spec spec, String raw) {
        if (raw == null) {
            if (spec.kind == Kind.BOOL) return true;
            throw new Bad(spec.allowed());
        }
        switch (spec.kind) {
            case INT -> {
                if (!PLAIN_INT.matcher(raw).matches()) throw new Bad(spec.allowed());
                long v = Long.parseLong(raw);
                if (v < spec.min || v > spec.max) throw new Bad(spec.allowed());
                return (int) v;
            }
            case BOOL -> {
                if (raw.equals("true")) return true;
                if (raw.equals("false")) return false;
                throw new Bad(spec.allowed());
            }
            case TEXT -> {
                if (raw.length() > NodeParser.MAX_TEXT || !visible(raw)) throw new Bad(spec.allowed());
                return raw;
            }
            case ICON -> {
                if (!NodeParser.ICON_NAMES.contains(raw)) throw new Bad(spec.allowed());
                return raw;
            }
            case IMAGE_SRC -> {
                if (!imageSrc(raw)) throw new Bad(spec.allowed());
                return raw;
            }
            case TABS, ARGS -> throw new Bad(spec.allowed());
            default -> {
                return raw;
            }
        }
    }

    // ============================================================
    //  绑定值：实例化时
    // ============================================================

    /** 绑定求出的值 → 放进 props 的值；返回 null 表示丢掉这个属性（已记 warn）。 */
    static Object normalize(Spec spec, Object v, EvalContext c, String tag) {
        String where = "<" + tag + "> 的 :" + spec.name;
        switch (spec.kind) {
            case INT -> {
                if (!(v instanceof Integer n)) return drop(c, where, v, spec);
                if (n < spec.min || n > spec.max) {
                    int clamped = Math.max(spec.min, Math.min(spec.max, n));
                    c.warn(where + " 得到 " + n + "，夹到 " + clamped);
                    return clamped;
                }
                return n;
            }
            case BOOL -> {
                return v instanceof Boolean ? v : drop(c, where, v, spec);
            }
            case TEXT -> {
                if (v instanceof List<?> || v instanceof Map<?, ?>) c.warn(where + " 得到 " + Values.kind(v) + "，显示为空");
                return visibleText(Values.text(v), c, where);
            }
            case STRING -> {
                return v instanceof String ? v : drop(c, where, v, spec);
            }
            case ICON -> {
                return v instanceof String s && NodeParser.ICON_NAMES.contains(s) ? s : drop(c, where, v, spec);
            }
            case IMAGE_SRC -> {
                return v instanceof String s && imageSrc(s) ? s : drop(c, where, v, spec);
            }
            case TABS -> {
                List<Map<String, Object>> tabs = tabs(v);
                return tabs != null ? tabs : drop(c, where, v, spec);
            }
            case ARGS -> {
                List<Object> args = args(v);
                return args != null ? args : drop(c, where, v, spec);
            }
            default -> {
                return drop(c, where, v, spec);
            }
        }
    }

    private static Object drop(EvalContext c, String where, Object v, Spec spec) {
        c.warn(where + " 得到 " + Values.kind(v) + "，不合规，按没写处理。允许：" + spec.allowed());
        return null;
    }

    /** 超长截断、控制字符换成空格，各记一条 warn。 */
    private static String visibleText(String s, EvalContext c, String where) {
        if (s.length() > NodeParser.MAX_TEXT) {
            c.warn(where + " 有 " + s.length() + " 字，截到 " + NodeParser.MAX_TEXT);
            s = Values.cut(s, NodeParser.MAX_TEXT);
        }
        if (!visible(s)) {
            c.warn(where + " 含控制字符，换成空格");
            StringBuilder sb = new StringBuilder(s);
            for (int i = 0; i < sb.length(); i++) {
                if (control(sb.charAt(i))) sb.setCharAt(i, ' ');
            }
            s = sb.toString();
        }
        return s;
    }

    private static List<Map<String, Object>> tabs(Object v) {
        if (!(v instanceof List<?> list) || list.size() < 2 || list.size() > 4) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> tab) || tab.size() != 1) return null;
            Map.Entry<?, ?> e = tab.entrySet().iterator().next();
            if (!TAB_FIELDS.contains(e.getKey()) || !(e.getValue() instanceof String s)) return null;
            if (e.getKey().equals("icon") ? !NodeParser.ICON_NAMES.contains(s)
                    : e.getKey().equals("text") && (s.length() > NodeParser.MAX_TEXT || !visible(s))) {
                return null;
            }
            out.add(Map.of((String) e.getKey(), s));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<Object> args(Object v) {
        if (!(v instanceof List<?> list) || list.size() > NodeParser.MAX_ARGS) return null;
        for (Object item : list) {
            if (!(item instanceof String) && !(item instanceof Integer)) return null;
        }
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    static boolean visible(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (control(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean control(char c) {
        return c != '\n' && (c < 0x20 || c == 0x7F);
    }

    private static boolean imageSrc(String s) {
        return s.endsWith(".png") && PackageError.PathRules.accept(s);
    }
}
