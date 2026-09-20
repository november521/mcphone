package com.november.mcphone.core.script.sfc;

import com.november.mcphone.core.script.layout.StateRules;
import com.november.mcphone.core.script.sfc.SfcError.Code;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 表达式的词法与语法（施工方案 §9.5.1），外加编译期能判定的检查：未声明的标识符、两边类型都已知却不支持的运算、
 * 调用与赋值、长度与节点数上限。{@link Statements} 与 {@link ScriptParser} 共用这里的词法。
 */
final class ExprParser {

    static final int MAX_LENGTH = 256;
    static final int MAX_NODES = 64;
    static final int MAX_ARRAY = 32;
    static final int MAX_OBJECT = 16;
    /** 256 字符里括号最多套 128 层；这道只防递归被别的入口带深。 */
    private static final int MAX_NESTING = 128;

    private static final Set<String> ASSIGNING = Set.of("=", "++", "--", "+=", "-=", "*=", "/=", "%=");

    /** 静态类型。ANY 是编译期不知道的（成员访问、下标、两支不同的三目），留给运行期判。 */
    enum T {
        INT("int"), STR("string"), BOOL("bool"), ARR("array"), OBJ("object"), NULL("null"), ANY("?");

        final String label;

        T(String label) {
            this.label = label;
        }

        static T of(Object v) {
            if (v == null) return NULL;
            if (v instanceof Integer) return INT;
            if (v instanceof String) return STR;
            if (v instanceof Boolean) return BOOL;
            if (v instanceof List<?>) return ARR;
            if (v instanceof Map<?, ?>) return OBJ;
            return ANY;
        }
    }

    /** 一段解析结果：AST、静态类型、数组时的元素类型、节点数。 */
    record Typed(Expr expr, T type, T elem, int nodes) {
    }

    /**
     * 宿主注入的只读名字（§13.8）。编译期就要知道它们是"声明过的"（否则模板里写
     * {@code backend.available} 会被当成拼错的 state 键拒掉），但<b>不能</b>写进 state、
     * 也不许赋值 —— 它们是宿主给的，不是 App 的数据。
     *
     * <p>目前只有一个 {@code backend}，形状是 {@code {available, serverName, actions}}；
     * 它不是安全边界，只是让作者能把"本服没部署"的分支画出来。
     */
    static final class Host {

        static final String BACKEND = "backend";

        private Host() {
        }

        static boolean is(String name) {
            return BACKEND.equals(name);
        }

        /** 编译期的静态类型；不是宿主名字返回 null。 */
        static T typeOf(String name) {
            return is(name) ? T.OBJ : null;
        }

        static java.util.Collection<String> names() {
            return java.util.List.of(BACKEND);
        }

        /**
         * 没有宿主喂值时的缺省形状：{@code available=false}、空名字、空动作表。
         * 形状齐全比缺键好 —— 表达式里的 {@code backend.actions.length} 在缺键时是 null，
         * 在缺省形状下是 0，后者才是"没有可用的动作"的正确表现。
         */
        static Map<String, Object> defaults() {
            return Map.of(BACKEND, Map.of(
                    "available", false,
                    "serverName", "",
                    "actions", java.util.List.of()));
        }

        /** 把宿主表补成"至少有一个形状齐全的 backend"。 */
        static Map<String, Object> withDefaults(Map<String, Object> host) {
            if (host != null && host.containsKey(BACKEND)) return host;
            Map<String, Object> out = new java.util.LinkedHashMap<>(defaults());
            if (host != null) out.putAll(host);
            return out;
        }
    }

    /** 编译期的名字表：state 的 key、宿主注入的只读名字与外层 v-for 的变量。不可变，进一层 v-for 包一层。 */
    static final class Scope {
        private final Map<String, Object> state;
        private final Scope parent;
        private final String name;
        private final T type;
        private final T elem;

        private Scope(Map<String, Object> state, Scope parent, String name, T type, T elem) {
            this.state = state;
            this.parent = parent;
            this.name = name;
            this.type = type;
            this.elem = elem;
        }

        static Scope of(Map<String, Object> state) {
            return new Scope(state, null, null, null, null);
        }

        Scope with(String name, T type, T elem) {
            return new Scope(state, this, name, type, elem);
        }

        boolean isState(String n) {
            return state.containsKey(n);
        }

        boolean isLoopVar(String n) {
            for (Scope s = this; s.parent != null; s = s.parent) {
                if (s.name.equals(n)) return true;
            }
            return false;
        }

        /** 没声明过返回 null。 */
        T typeOf(String n) {
            for (Scope s = this; s.parent != null; s = s.parent) {
                if (s.name.equals(n)) return s.type;
            }
            if (state.containsKey(n)) return T.of(state.get(n));
            return Host.typeOf(n);
        }

        /** state 数组的元素按初值推：写入必须与初值同形（UiState.writeProblem / set），推断才成立。初值是空数组时不知道。 */
        T elemOf(String n) {
            for (Scope s = this; s.parent != null; s = s.parent) {
                if (s.name.equals(n)) return s.elem;
            }
            if (!(state.get(n) instanceof List<?> list)) return T.ANY;
            Object r = StateRules.representative(list);
            return r == null ? T.ANY : T.of(r);
        }

        Object initial(String n) {
            return state.get(n);
        }

        Collection<String> names() {
            Set<String> out = new TreeSet<>(state.keySet());
            for (Scope s = this; s.parent != null; s = s.parent) out.add(s.name);
            out.addAll(Host.names());
            return out;
        }
    }

    /** 词法单元。kind：i 整数、s 字符串、n 名字、p 符号、e 结尾。 */
    record Tok(char kind, String text, Object value, int line, int col, int offset) {
    }

    private final String src;
    private final boolean comments;
    private final List<Tok> toks = new ArrayList<>();
    private int at;
    private int lexLine;
    private int lexCol;
    private int pos;
    private Scope scope;
    private int nesting;

    /** line / col 是 src 第一个字符在块内的位置，报错按它往后数。comments 只有 script 块开：模板表达式里没有注释。 */
    ExprParser(String src, int line, int col, boolean comments) {
        this.src = src;
        this.comments = comments;
        this.lexLine = line;
        this.lexCol = col;
    }

    /** 编译一条模板表达式：长度 → 语法与静态检查 → 必须读完 → 节点数。 */
    static Typed expression(String src, Scope scope, int line, int col) {
        if (src.length() > MAX_LENGTH) throw SfcError.at(Code.E_EXPR_TOO_LONG, line, col);
        ExprParser p = new ExprParser(src, line, col, false);
        if (p.peek().kind == 'e') throw syntax(p.peek(), "表达式是空的");
        Typed t = p.parse(scope);
        p.end();
        if (t.nodes > MAX_NODES) throw SfcError.at(Code.E_EXPR_TOO_COMPLEX, line, col, t.nodes);
        return t;
    }

    // ============================================================
    //  词法单元的读取（Statements / ScriptParser 也用）
    // ============================================================

    Tok peek() {
        return token(pos);
    }

    Tok peek(int ahead) {
        return token(pos + ahead);
    }

    Tok next() {
        Tok t = token(pos);
        if (t.kind != 'e') pos++;
        return t;
    }

    /** 按需往后读：词法错只在读到那里时才报，于是前面先写错的地方先报。 */
    private Tok token(int index) {
        while (toks.size() <= index) {
            if (!toks.isEmpty() && toks.get(toks.size() - 1).kind == 'e') return toks.get(toks.size() - 1);
            toks.add(lexOne());
        }
        return toks.get(index);
    }

    boolean at(String punct) {
        return is(peek(), punct);
    }

    static boolean is(Tok t, String punct) {
        return t.kind == 'p' && t.text.equals(punct);
    }

    Tok expect(String punct) {
        Tok t = next();
        if (!is(t, punct)) throw unexpected(t, "这里要 '" + punct + "'");
        return t;
    }

    /** 读完了吗；没读完时按剩下的第一个记号给出最贴切的错。 */
    void end() {
        Tok t = peek();
        if (t.kind == 'e') return;
        if (t.kind == 'p' && ASSIGNING.contains(t.text)) throw SfcError.at(Code.E_EXPR_NO_ASSIGNMENT, t.line, t.col);
        throw unexpected(t, "多出来的 '" + t.text + "'");
    }

    // ============================================================
    //  表达式
    // ============================================================

    Typed parse(Scope scope) {
        this.scope = scope;
        return expr();
    }

    private Typed expr() {
        deeper();
        Typed test = or();
        if (at("?")) {
            next();
            Typed a = expr();
            expect(":");
            Typed b = expr();
            test = new Typed(new Expr.Cond(test.expr, a.expr, b.expr),
                    a.type == b.type ? a.type : T.ANY, a.elem == b.elem ? a.elem : T.ANY,
                    1 + test.nodes + a.nodes + b.nodes);
        }
        nesting--;
        return test;
    }

    private Typed or() {
        Typed l = and();
        while (at("||")) {
            next();
            Typed r = and();
            l = new Typed(new Expr.Logic(false, l.expr, r.expr), T.BOOL, T.ANY, 1 + l.nodes + r.nodes);
        }
        return l;
    }

    private Typed and() {
        Typed l = equality();
        while (at("&&")) {
            next();
            Typed r = equality();
            l = new Typed(new Expr.Logic(true, l.expr, r.expr), T.BOOL, T.ANY, 1 + l.nodes + r.nodes);
        }
        return l;
    }

    private Typed equality() {
        Typed l = relational();
        while (at("==") || at("!=")) {
            Tok op = next();
            Typed r = relational();
            l = binary(op, l, r, T.BOOL);
        }
        return l;
    }

    private Typed relational() {
        Typed l = additive();
        while (at("<") || at("<=") || at(">") || at(">=")) {
            Tok op = next();
            Typed r = additive();
            if (known(l.type) && known(r.type)
                    && !(l.type == T.INT && r.type == T.INT) && !(l.type == T.STR && r.type == T.STR)) {
                throw typeError(op, l.type, r.type);
            }
            l = binary(op, l, r, T.BOOL);
        }
        return l;
    }

    private Typed additive() {
        Typed l = multiplicative();
        while (at("+") || at("-")) {
            Tok op = next();
            Typed r = multiplicative();
            T type;
            if (op.text.equals("-")) {
                type = numeric(op, l, r);
            } else if (l.type == T.STR || r.type == T.STR) {
                type = T.STR;
            } else if (l.type == T.INT && r.type == T.INT) {
                type = T.INT;
            } else if (!known(l.type) || !known(r.type)) {
                type = T.ANY;
            } else {
                throw typeError(op, l.type, r.type);
            }
            l = binary(op, l, r, type);
        }
        return l;
    }

    private Typed multiplicative() {
        Typed l = unary();
        while (at("*") || at("/") || at("%")) {
            Tok op = next();
            Typed r = unary();
            l = binary(op, l, r, numeric(op, l, r));
        }
        return l;
    }

    private Typed unary() {
        Tok t = peek();
        if (is(t, "!") || is(t, "-")) {
            next();
            deeper();
            Typed out;
            if (is(t, "-") && peek().kind == 'i') {
                // 常量折叠：-2147483648 只能这样写出来，2147483648 本身不是 int
                Tok n = next();
                out = new Typed(new Expr.Lit((int) -(Long) n.value), T.INT, T.ANY, 1);
                out = postfixOn(out);
            } else {
                Typed u = unary();
                if (is(t, "-") && known(u.type) && u.type != T.INT) {
                    throw SfcError.at(Code.E_EXPR_TYPE, t.line, t.col, "-", u.type.label, "（一元）");
                }
                out = new Typed(new Expr.Unary(t.text.charAt(0), u.expr), is(t, "!") ? T.BOOL : T.INT, T.ANY,
                        1 + u.nodes);
            }
            nesting--;
            return out;
        }
        if (t.kind == 'p' && ASSIGNING.contains(t.text)) throw SfcError.at(Code.E_EXPR_NO_ASSIGNMENT, t.line, t.col);
        return postfixOn(primary());
    }

    private Typed postfixOn(Typed t) {
        while (true) {
            Tok op = peek();
            if (is(op, ".")) {
                next();
                Tok name = next();
                if (name.kind != 'n') throw unexpected(name, "'.' 后面要写名字");
                if (at("(")) throw SfcError.at(Code.E_EXPR_NO_CALLS, name.line, name.col);
                boolean length = name.text.equals("length") && (t.type == T.ARR || t.type == T.STR);
                t = new Typed(new Expr.Member(t.expr, name.text), length ? T.INT : T.ANY, T.ANY, t.nodes + 1);
            } else if (is(op, "[")) {
                next();
                Typed i = expr();
                expect("]");
                t = new Typed(new Expr.Index(t.expr, i.expr), T.ANY, T.ANY, t.nodes + i.nodes + 1);
            } else if (is(op, "(")) {
                throw SfcError.at(Code.E_EXPR_NO_CALLS, op.line, op.col);
            } else if (is(op, "++") || is(op, "--")) {
                throw SfcError.at(Code.E_EXPR_NO_ASSIGNMENT, op.line, op.col);
            } else {
                return t;
            }
        }
    }

    private Typed primary() {
        Tok t = next();
        switch (t.kind) {
            case 'i' -> {
                long v = (Long) t.value;
                if (v > Integer.MAX_VALUE) throw syntax(t, "整数超出 int 范围");
                return new Typed(new Expr.Lit((int) v), T.INT, T.ANY, 1);
            }
            case 's' -> {
                return new Typed(new Expr.Lit(t.value), T.STR, T.ANY, 1);
            }
            case 'n' -> {
                switch (t.text) {
                    case "true", "false" -> {
                        return new Typed(new Expr.Lit(t.text.equals("true")), T.BOOL, T.ANY, 1);
                    }
                    case "null" -> {
                        return new Typed(new Expr.Lit(null), T.NULL, T.ANY, 1);
                    }
                    default -> {
                        // 先看调用：f(1) 该说「不支持调用」，而不是「不认识 f」
                        if (at("(")) throw SfcError.at(Code.E_EXPR_NO_CALLS, t.line, t.col);
                        T type = scope.typeOf(t.text);
                        if (type == null) throw unknownIdent(t, scope.names());
                        return new Typed(new Expr.Ref(t.text), type, scope.elemOf(t.text), 1);
                    }
                }
            }
            default -> {
            }
        }
        if (is(t, "(")) {
            Typed e = expr();
            expect(")");
            return e;
        }
        if (is(t, "[")) return array();
        if (is(t, "{")) return object();
        if (t.kind == 'p' && ASSIGNING.contains(t.text)) throw SfcError.at(Code.E_EXPR_NO_ASSIGNMENT, t.line, t.col);
        throw unexpected(t, t.kind == 'e' ? "表达式没写完" : "这里不能出现 '" + t.text + "'");
    }

    private Typed array() {
        List<Expr> items = new ArrayList<>();
        int nodes = 1;
        T elem = null;
        if (!at("]")) {
            while (true) {
                Typed item = expr();
                if (items.size() == MAX_ARRAY) throw syntax(peek(), "数组最多 " + MAX_ARRAY + " 项");
                items.add(item.expr);
                nodes += item.nodes;
                elem = elem == null || elem == item.type ? item.type : T.ANY;
                if (at(",")) {
                    next();
                    continue;
                }
                break;
            }
        }
        expect("]");
        return new Typed(new Expr.ArrayLit(List.copyOf(items)), T.ARR, elem == null ? T.ANY : elem, nodes);
    }

    private Typed object() {
        List<String> keys = new ArrayList<>();
        List<Expr> values = new ArrayList<>();
        int nodes = 1;
        if (!at("}")) {
            while (true) {
                Tok key = next();
                if (key.kind != 'n') throw unexpected(key, "对象的键写成不带引号的名字");
                if (keys.contains(key.text)) throw syntax(key, "键 '" + key.text + "' 写了两次");
                if (keys.size() == MAX_OBJECT) throw syntax(key, "对象最多 " + MAX_OBJECT + " 个键");
                expect(":");
                Typed v = expr();
                keys.add(key.text);
                values.add(v.expr);
                nodes += v.nodes;
                if (at(",")) {
                    next();
                    continue;
                }
                break;
            }
        }
        expect("}");
        return new Typed(new Expr.ObjectLit(List.copyOf(keys), List.copyOf(values)), T.OBJ, T.ANY, nodes);
    }

    private Typed binary(Tok op, Typed l, Typed r, T type) {
        return new Typed(new Expr.Binary(op.text, l.expr, r.expr), type, T.ANY, 1 + l.nodes + r.nodes);
    }

    private T numeric(Tok op, Typed l, Typed r) {
        if (known(l.type) && known(r.type) && !(l.type == T.INT && r.type == T.INT)) throw typeError(op, l.type, r.type);
        if (known(l.type) && l.type != T.INT) throw typeError(op, l.type, r.type);
        if (known(r.type) && r.type != T.INT) throw typeError(op, l.type, r.type);
        return T.INT;
    }

    private void deeper() {
        if (++nesting > MAX_NESTING) throw SfcError.at(Code.E_EXPR_TOO_COMPLEX, peek().line, peek().col, nesting);
    }

    private static boolean known(T t) {
        return t != T.ANY;
    }

    // ============================================================
    //  <script> 里的字面量（§9.7）
    // ============================================================

    /** state 的一个初值。只收字面量：名字、运算、调用一律 E_SCRIPT_P0_SUBSET。depth 是这个值所在的容器层数，从 1 起。 */
    Object literal(String key, int depth) {
        Tok t = next();
        if (t.kind == 'i') {
            long v = (Long) t.value;
            if (v > Integer.MAX_VALUE) throw syntax(t, "整数超出 int 范围");
            return (int) v;
        }
        if (t.kind == 's') return t.value;
        if (t.kind == 'n' && (t.text.equals("true") || t.text.equals("false"))) return t.text.equals("true");
        if (t.kind == 'n' && t.text.equals("null")) {
            throw SfcError.at(Code.E_SCRIPT_STATE, t.line, t.col, key, "初值不能是 null");
        }
        if (is(t, "-") && peek().kind == 'i') return (int) -(Long) next().value;
        if (is(t, "[") || is(t, "{")) {
            if (depth > StateRules.MAX_DEPTH) {
                throw SfcError.at(Code.E_SCRIPT_STATE, t.line, t.col, key, "嵌套超过 " + StateRules.MAX_DEPTH + " 层");
            }
            return is(t, "[") ? literalArray(key, depth) : literalObject(key, depth);
        }
        throw SfcError.at(Code.E_SCRIPT_P0_SUBSET, t.line, t.col);
    }

    private List<Object> literalArray(String key, int depth) {
        List<Object> out = new ArrayList<>();
        while (!at("]")) {
            Tok at = peek();
            if (out.size() == StateRules.MAX_ARRAY) {
                throw SfcError.at(Code.E_SCRIPT_STATE, at.line, at.col, key, "数组最多 " + StateRules.MAX_ARRAY + " 项");
            }
            out.add(literal(key, depth + 1));
            if (!at(",")) break;
            next();
        }
        literalClose("]");
        return out;
    }

    private Map<String, Object> literalObject(String key, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        while (!at("}")) {
            Tok k = next();
            if (k.kind != 'n' && k.kind != 's') throw SfcError.at(Code.E_SCRIPT_P0_SUBSET, k.line, k.col);
            String name = k.kind == 's' ? (String) k.value : k.text;
            if (out.containsKey(name)) {
                throw SfcError.at(Code.E_SCRIPT_STATE, k.line, k.col, key, "键 '" + name + "' 写了两次");
            }
            if (out.size() == StateRules.MAX_OBJECT_KEYS) {
                throw SfcError.at(Code.E_SCRIPT_STATE, k.line, k.col, key, "对象最多 " + StateRules.MAX_OBJECT_KEYS + " 个键");
            }
            literalClose(":");
            out.put(name, literal(key, depth + 1));
            if (!at(",")) break;
            next();
        }
        literalClose("}");
        return out;
    }

    private void literalClose(String punct) {
        Tok t = next();
        if (!is(t, punct)) throw SfcError.at(Code.E_SCRIPT_P0_SUBSET, t.line, t.col);
    }

    // ============================================================
    //  报错
    // ============================================================

    static SfcError syntax(Tok at, String reason) {
        return SfcError.at(Code.E_EXPR_SYNTAX, at.line, at.col, reason);
    }

    static SfcError unknownIdent(Tok t, Collection<String> declared) {
        return SfcError.at(Code.E_EXPR_UNKNOWN_IDENT, t.line, t.col, t.text, Names.hint(t.text, declared),
                Names.list(declared));
    }

    private static SfcError typeError(Tok op, T l, T r) {
        return SfcError.at(Code.E_EXPR_TYPE, op.line, op.col, op.text, l.label, r.label);
    }

    /** JS 里有、这里刻意没有的写法（§9.5.2），报的时候直接说换成什么。 */
    private static SfcError unexpected(Tok t, String fallback) {
        if (t.kind != 'p') return syntax(t, fallback);
        String hint = switch (t.text) {
            case "===" -> "没有 ===，用 ==（本来就不做类型转换）";
            case "!==" -> "没有 !==，用 !=";
            case "??", "?." -> "没有 ?? 和 ?.：访问不存在的键本来就得 null";
            case "&", "|", "^", "~", "<<", ">>" -> "没有位运算";
            case "`" -> "没有模板字符串，用 + 拼";
            case "=>" -> "没有箭头函数，逻辑写在 <script> 里（P1）";
            case "**" -> "没有 **";
            case ";" -> "表达式里不能写 ;";
            default -> fallback;
        };
        return syntax(t, hint);
    }

    // ============================================================
    //  词法
    // ============================================================

    private static final String[] PUNCTS = {
            "===", "!==", "==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=",
            "=>", "?.", "??", "**", "<<", ">>",
            "+", "-", "*", "/", "%", "<", ">", "!", "?", ":", ".", ",", "(", ")", "[", "]", "{", "}", "=", ";",
            "&", "|", "^", "~", "`"};

    private Tok lexOne() {
        String s = src;
        int n = s.length();
        int i = at;
        int line = lexLine;
        int col = lexCol;
        Tok out;
        {
            while (i < n) {
                char c = s.charAt(i);
                if (c == '\n') {
                    line++;
                    col = 1;
                    i++;
                } else if (c == ' ' || c == '\t' || c == '\r') {
                    col++;
                    i++;
                } else if (comments && c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                    while (i < n && s.charAt(i) != '\n') {
                        i++;
                        col++;
                    }
                } else if (comments && c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                    int sl = line;
                    int sc = col;
                    i += 2;
                    col += 2;
                    boolean closed = false;
                    while (i < n) {
                        if (s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/') {
                            i += 2;
                            col += 2;
                            closed = true;
                            break;
                        }
                        if (s.charAt(i) == '\n') {
                            line++;
                            col = 1;
                        } else {
                            col++;
                        }
                        i++;
                    }
                    if (!closed) throw SfcError.at(Code.E_EXPR_SYNTAX, sl, sc, "/* 注释没有收尾");
                } else {
                    break;
                }
            }
            if (i >= n) {
                out = new Tok('e', "", null, line, col, i);
                at = i;
                lexLine = line;
                lexCol = col;
                return out;
            }
            char c = s.charAt(i);
            int start = i;
            int sl = line;
            int sc = col;
            if (c >= '0' && c <= '9') {
                long v = 0;
                while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                    v = Math.min(v * 10 + (s.charAt(i) - '0'), 1L << 40);
                    i++;
                }
                if (i < n && (identPart(s.charAt(i)) || s.charAt(i) == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                    throw SfcError.at(Code.E_EXPR_SYNTAX, sl, sc, "数字后面不能紧跟 '" + shown(s.charAt(i)) + "'：没有小数，也没有单位");
                }
                if (c == '0' && i - start > 1) throw SfcError.at(Code.E_EXPR_SYNTAX, sl, sc, "整数不写前导 0");
                if (v > 2147483648L) throw SfcError.at(Code.E_EXPR_SYNTAX, sl, sc, "整数超出 int 范围");
                col += i - start;
                out = new Tok('i', s.substring(start, i), v, sl, sc, start);
            } else if (c == '\'' || c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                col++;
                while (true) {
                    if (i >= n || s.charAt(i) == '\n') throw SfcError.at(Code.E_EXPR_SYNTAX, sl, sc, "字符串没有收尾的引号");
                    char d = s.charAt(i);
                    if (d == c) {
                        i++;
                        col++;
                        break;
                    }
                    if (d == '\\') {
                        char e = i + 1 < n ? s.charAt(i + 1) : '\n';
                        switch (e) {
                            case '\\', '\'', '"' -> sb.append(e);
                            case 'n' -> sb.append('\n');
                            case 't' -> sb.append('\t');
                            default -> throw SfcError.at(Code.E_EXPR_SYNTAX, line, col, "不认识的转义 \\" + shown(e));
                        }
                        i += 2;
                        col += 2;
                    } else {
                        sb.append(d);
                        i++;
                        col++;
                    }
                }
                out = new Tok('s', s.substring(start, i), sb.toString(), sl, sc, start);
            } else if (identStart(c)) {
                while (i < n && identPart(s.charAt(i))) i++;
                col += i - start;
                out = new Tok('n', s.substring(start, i), null, sl, sc, start);
            } else {
                String p = null;
                for (String candidate : PUNCTS) {
                    if (s.startsWith(candidate, i)) {
                        p = candidate;
                        break;
                    }
                }
                if (p == null) throw SfcError.at(Code.E_EXPR_SYNTAX, sl, sc, "不认识的字符 '" + shown(c) + "'");
                i += p.length();
                col += p.length();
                out = new Tok('p', p, null, sl, sc, start);
            }
        }
        at = i;
        lexLine = line;
        lexCol = col;
        return out;
    }

    static boolean identStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
    }

    static boolean identPart(char c) {
        return identStart(c) || (c >= '0' && c <= '9');
    }

    /** 看不见的字符写成 U+XXXX，否则报错文案里是一片空白。 */
    static String shown(char c) {
        int type = Character.getType(c);
        if (c < 0x20 || c == 0x7F || type == Character.FORMAT || type == Character.SPACE_SEPARATOR
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE || type == Character.PRIVATE_USE || type == Character.UNASSIGNED) {
            return String.format(java.util.Locale.ROOT, "U+%04X", (int) c);
        }
        return String.valueOf(c);
    }
}
