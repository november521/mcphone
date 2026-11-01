package com.november.mcphone.core.script.sfc;

import com.november.mcphone.core.script.engine.ClientScript;
import com.november.mcphone.core.script.http.HttpRequests;
import com.november.mcphone.core.script.timer.DelayedCallbacks;
import com.november.mcphone.core.script.layout.UiState;
import com.november.mcphone.core.script.layout.PageParameters;
import com.november.mcphone.core.script.server.store.LocalStore;
import com.november.mcphone.core.script.sfc.ExprParser.Scope;
import com.november.mcphone.core.script.sfc.ExprParser.Tok;
import com.november.mcphone.core.script.sfc.ExprParser.Typed;
import com.november.mcphone.core.script.sfc.SfcError.Code;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code @click} 的值（施工方案 §9.6）：赋值、自增减、内建操作或本页函数，
 * 用 ; 连接最多 4 条。它不是表达式语言的一部分，所以 {@code {{ count++ }}} 报的是不能赋值。
 *
 * <p>按书写顺序在一份副本上执行，全部成功才写回 state：半执行的状态比不执行更难排查。
 */
public final class Statements {

    public static final int MAX_STATEMENTS = 4;

    /** {@code call(...)} 里动作 id 的长度上限，与 {@code ScriptProtocol.ID_MAX} 一致（那份是线格式，这里是编译期）。 */
    public static final int MAX_ACTION_LEN = 64;

    private static final String SHAPES =
            "@click 只能写 x = 表达式、x++、x--、close()、back()、nav('页面')、nav('页面', {id:1})、call('动作') 或本页函数";

    sealed interface Stmt permits Assign, Step, Close, Back, Nav, Call, LocalCall {
    }

    record Assign(String key, Expr.Compiled value) implements Stmt {
    }

    record Step(String key, int delta) implements Stmt {
    }

    record Close() implements Stmt {
    }

    record Back() implements Stmt {
    }

    record Nav(Expr.Compiled page, Expr.Compiled parameters) implements Stmt {
    }

    /** 宿主转发的一条 RPC（§15.1）。参数与回调还写不了（P1 的 <script>），这里只点一下发空参数。 */
    record Call(String actionId) implements Stmt {
    }

    record LocalCall(String name, List<Expr.Compiled> arguments) implements Stmt {
    }

    /**
     * 一次点击的结果。applied 为 false 时 state 一个都没改，close / back / nav / call 也都不算数。
     * close、back、nav 同时出现时由页面决定先后，这里只如实报出写了哪些。
     * {@code calls} 是要宿主发出去的动作 id，按书写顺序。
     */
    public record Outcome(boolean applied, boolean close, boolean back, String nav,
                          List<String> calls, List<String> warnings, PageParameters navParameters) {
        public Outcome(boolean applied, boolean close, boolean back, String nav, List<String> calls, List<String> warnings) {
            this(applied, close, back, nav, calls, warnings, PageParameters.EMPTY);
        }
    }

    /** 实例化时绑好 v-for 变量的一条 @click，点击时执行。 */
    public record Bound(Statements statements, List<String> names, List<Object> values,
                        java.util.Map<String, Object> host) {
        public Outcome run(UiState state, String file) {
            try (LocalStore.Transaction storage = new LocalStore.Transaction(null)) {
                return run(state, file, storage);
            }
        }

        public Outcome run(UiState state, String file, LocalStore.Transaction storage) {
            return run(state, file, storage, null);
        }

        public Outcome run(UiState state, String file, LocalStore.Transaction storage, HttpRequests requests) {
            return run(state, file, storage, requests, null);
        }

        public Outcome run(UiState state, String file, LocalStore.Transaction storage, HttpRequests requests,
                           DelayedCallbacks.Batch timers) {
            return statements.run(state, file, names, values, host, storage, requests, timers);
        }
    }

    private final List<Stmt> list;
    private final int line;
    private final ClientScript script;
    private final boolean input;

    private Statements(List<Stmt> list, int line, ClientScript script, boolean input) {
        this.list = list;
        this.line = line;
        this.script = script;
        this.input = input;
    }

    List<Stmt> list() {
        return list;
    }

    static Statements parse(String src, Scope scope, int line, int col) {
        return parse(src, scope, line, col, null);
    }

    static Statements parse(String src, Scope scope, int line, int col, ClientScript script) {
        return parse(src, scope, line, col, script, false);
    }

    /** Input events invoke one declared page function; navigation and RPC statements remain click actions. */
    static Statements parseInput(String src, Scope scope, int line, int col, ClientScript script) {
        return parse(src, scope, line, col, script, true);
    }

    private static Statements parse(String src, Scope scope, int line, int col, ClientScript script, boolean input) {
        String event = input ? "@input" : "@click";
        // 只防超大输入；单条语句的 256 字符在下面按记号量，空白不算
        if (src.length() > MAX_STATEMENTS * ExprParser.MAX_LENGTH * 4) throw SfcError.at(Code.E_EXPR_TOO_LONG, line, col);
        ExprParser p = new ExprParser(src, line, col, false);
        if (p.peek().kind() == 'e') throw ExprParser.syntax(p.peek(), event + " 是空的");
        List<Stmt> out = new ArrayList<>();
        while (p.peek().kind() != 'e') {
            Tok first = p.peek();
            if (out.size() == MAX_STATEMENTS) throw ExprParser.syntax(first, "@click 最多 " + MAX_STATEMENTS + " 条语句");
            if (span(p) > ExprParser.MAX_LENGTH) throw SfcError.at(Code.E_EXPR_TOO_LONG, first.line(), first.col());
            Stmt next = statement(p, scope, src, script);
            if (input && (!(next instanceof LocalCall) || !out.isEmpty())) {
                throw ExprParser.syntax(first, "@input 只接受一次本页函数调用，例如 changed()");
            }
            out.add(next);
            if (p.at(";")) {
                p.next();
            } else if (p.peek().kind() != 'e') {
                throw ExprParser.syntax(p.peek(), "两条语句之间要用 ; 隔开");
            }
        }
        return new Statements(List.copyOf(out), line, script, input);
    }

    private static Stmt statement(ExprParser p, Scope scope, String src, ClientScript script) {
        Tok t = p.next();
        if (t.kind() != 'n') throw ExprParser.syntax(t, SHAPES);
        Tok after = p.peek();
        String name = t.text();

        if (ExprParser.is(after, "(")) {
            switch (name) {
                case "close", "back" -> {
                    p.next();
                    p.expect(")");
                    return name.equals("close") ? new Close() : new Back();
                }
                case "nav" -> {
                    p.next();
                    Typed page = p.parse(scope);
                    Expr.Compiled parameters = null;
                    if (p.at(",")) {
                        p.next();
                        Tok at = p.peek();
                        Typed value = p.parse(scope);
                        if (value.type() != ExprParser.T.OBJ && value.type() != ExprParser.T.ANY) {
                            throw ExprParser.syntax(at, "nav 的第二个参数必须是普通对象");
                        }
                        if (page.nodes() + value.nodes() + 1 > ExprParser.MAX_NODES) {
                            throw SfcError.at(Code.E_EXPR_TOO_COMPLEX, at.line(), at.col(), page.nodes() + value.nodes() + 1);
                        }
                        parameters = compiled(value, at, src);
                    }
                    p.expect(")");
                    return new Nav(compiled(page, t, src), parameters);
                }
                case "call" -> {
                    p.next();
                    Tok arg = p.next();
                    if (arg.kind() != 's') throw ExprParser.syntax(arg, "call(...) 要一个动作名字符串，比如 call('claim_daily')");
                    String action = (String) arg.value();
                    if (action.isEmpty() || action.length() > MAX_ACTION_LEN) {
                        throw ExprParser.syntax(arg, "动作名要 1–" + MAX_ACTION_LEN + " 个字符");
                    }
                    for (int i = 0; i < action.length(); i++) {
                        if (Character.isISOControl(action.charAt(i))) {
                            throw ExprParser.syntax(arg, "动作名里不能有控制字符");
                        }
                    }
                    p.expect(")");
                    return new Call(action);
                }
                default -> {
                    if (script == null || !script.declares(name)) throw SfcError.at(Code.E_EXPR_NO_CALLS, t.line(), t.col());
                    p.next();
                    List<Expr.Compiled> arguments = new ArrayList<>();
                    int nodes = 1;
                    if (!p.at(")")) {
                        do {
                            Tok at = p.peek();
                            if (arguments.size() == ClientScript.MAX_PARAMETERS) throw ExprParser.syntax(at, "客户端函数最多 4 个参数");
                            Typed argument = p.parse(scope);
                            if (argument.type() == ExprParser.T.ARR || argument.type() == ExprParser.T.OBJ || argument.type() == ExprParser.T.NULL) {
                                throw ExprParser.syntax(at, "客户端函数参数只能是字符串、整数或布尔值");
                            }
                            nodes += argument.nodes();
                            if (nodes > ExprParser.MAX_NODES) throw SfcError.at(Code.E_EXPR_TOO_COMPLEX, at.line(), at.col(), nodes);
                            arguments.add(compiled(argument, at, src));
                            if (!p.at(",")) break;
                            p.next();
                        } while (true);
                    }
                    p.expect(")");
                    if (arguments.size() != script.arity(name)) throw ExprParser.syntax(t,
                            "客户端函数 '" + name + "' 需要 " + script.arity(name) + " 个参数，得到 " + arguments.size() + " 个");
                    return new LocalCall(name, List.copyOf(arguments));
                }
            }
        }

        if (ExprParser.is(after, "=") || ExprParser.is(after, "++") || ExprParser.is(after, "--")) {
            if (ExprParser.Host.is(name)) throw ExprParser.syntax(t, name + " 是宿主注入的只读对象，不能赋值");
            if (scope.isLoopVar(name)) throw ExprParser.syntax(t, "v-for 的变量 '" + name + "' 不能赋值，只能赋给 state 里的 key");
            if (!scope.isState(name)) throw ExprParser.unknownIdent(t, scope.names());
            p.next();
            if (ExprParser.is(after, "=")) return new Assign(name, compiled(p.parse(scope), t, src));
            return new Step(name, ExprParser.is(after, "++") ? 1 : -1);
        }
        if (after.kind() == 'p' && after.text().length() == 2 && after.text().charAt(1) == '=' && "+-*/%".indexOf(after.text().charAt(0)) >= 0) {
            throw ExprParser.syntax(after, "没有 " + after.text() + "，写成 " + name + " = " + name + " " + after.text().charAt(0) + " …");
        }
        throw ExprParser.syntax(t, SHAPES);
    }

    /** 这一条语句从第一个记号到顶层 ; 之前最后一个记号的长度。先量再解析：超长的不必解析完才拒（嵌套过深会先撞别的错）。 */
    private static int span(ExprParser p) {
        Tok first = p.peek();
        Tok last = first;
        int depth = 0;
        for (int k = 0; ; k++) {
            Tok t = p.peek(k);
            if (t.kind() == 'e') break;
            if (t.kind() == 'p') {
                switch (t.text()) {
                    case "(", "[", "{" -> depth++;
                    case ")", "]", "}" -> depth--;
                    case ";" -> {
                        if (depth <= 0) return end(last) - first.offset();
                    }
                    default -> {
                    }
                }
            }
            last = t;
            if (end(t) - first.offset() > ExprParser.MAX_LENGTH) break;
        }
        return end(last) - first.offset();
    }

    private static int end(Tok t) {
        return t.offset() + t.text().length();
    }

    /** 运行期 warn 用的行号整体下移（块内行 → 原文件行，§9.8）。编译期的报错由 SfcCompiler 统一加偏移，不走这里。 */
    Statements shifted(int lines) {
        if (lines == 0) return this;
        List<Stmt> moved = new ArrayList<>();
        for (Stmt s : list) {
            if (s instanceof Assign a) {
                moved.add(new Assign(a.key(), shift(a.value(), lines)));
            } else if (s instanceof Nav n) {
                moved.add(new Nav(shift(n.page(), lines), n.parameters() == null ? null : shift(n.parameters(), lines)));
            } else if (s instanceof LocalCall call) {
                moved.add(new LocalCall(call.name(), call.arguments().stream().map(a -> shift(a, lines)).toList()));
            } else {
                moved.add(s);
            }
        }
        return new Statements(List.copyOf(moved), line + lines, script, input);
    }

    static Expr.Compiled shift(Expr.Compiled c, int lines) {
        return new Expr.Compiled(c.root(), c.line() + lines, c.source());
    }

    private static Expr.Compiled compiled(Typed t, Tok at, String src) {
        if (t.nodes() > ExprParser.MAX_NODES) throw SfcError.at(Code.E_EXPR_TOO_COMPLEX, at.line(), at.col(), t.nodes());
        return new Expr.Compiled(t.expr(), at.line(), src);
    }

    Outcome run(UiState state, String file, List<String> names, List<Object> values,
                Map<String, Object> host) {
        try (LocalStore.Transaction storage = new LocalStore.Transaction(null)) {
            return run(state, file, names, values, host, storage);
        }
    }

    private Outcome run(UiState state, String file, List<String> names, List<Object> values,
                        Map<String, Object> host, LocalStore.Transaction storage) {
        return run(state, file, names, values, host, storage, null);
    }

    private Outcome run(UiState state, String file, List<String> names, List<Object> values,
                        Map<String, Object> host, LocalStore.Transaction storage, HttpRequests requests) {
        return run(state, file, names, values, host, storage, requests, null);
    }

    private Outcome run(UiState state, String file, List<String> names, List<Object> values,
                        Map<String, Object> host, LocalStore.Transaction storage, HttpRequests requests,
                        DelayedCallbacks.Batch timers) {
        Map<String, Object> scratch = new LinkedHashMap<>(state.values());
        EvalContext c = new EvalContext(scratch, host, file);
        for (int i = 0; i < names.size(); i++) c.push(names.get(i), values.get(i));
        boolean close = false;
        boolean back = false;
        String nav = null;
        PageParameters navParameters = PageParameters.EMPTY;
        List<String> calls = new ArrayList<>();
        Set<String> changed = new LinkedHashSet<>();
        for (Stmt s : list) {
            c.line = line;
            if (s instanceof Assign a) {
                Object v = a.value().run(c);
                // §9.7 的值规则不只管初值，形状也对着初值比：编译期按初值推断的类型靠它成立
                String why = state.writeProblem(a.key(), v);
                if (why != null) return failed(c, why);
                scratch.put(a.key(), v);
                changed.add(a.key());
            } else if (s instanceof Step st) {
                if (!(scratch.get(st.key()) instanceof Integer n)) {
                    return failed(c, st.key() + (st.delta() > 0 ? "++" : "--") + " 只能用在 int 上，它是 " + Values.kind(scratch.get(st.key())));
                }
                scratch.put(st.key(), n + st.delta());
                changed.add(st.key());
            } else if (s instanceof Nav nv) {
                Object v = nv.page().run(c);
                if (!(v instanceof String page)) return failed(c, "nav(...) 要页面名字符串，得到的是 " + Values.kind(v));
                try {
                    navParameters = nv.parameters() == null ? PageParameters.EMPTY : PageParameters.from(nv.parameters().run(c));
                } catch (IllegalArgumentException e) {
                    return failed(c, e.getMessage());
                }
                nav = page;
            } else if (s instanceof Call call) {
                calls.add(call.actionId());
            } else if (s instanceof LocalCall call) {
                try {
                    List<Object> arguments = new ArrayList<>();
                    for (Expr.Compiled argument : call.arguments()) arguments.add(argument.run(c));
                    Map<String, Object> result = script.invoke(call.name(), arguments, scratch, state, storage, requests, timers);
                    scratch.putAll(result);
                    changed.addAll(result.keySet());
                } catch (ClientScript.Failure failure) {
                    return failed(c, failure.getMessage());
                }
            } else if (s instanceof Close) {
                close = true;
            } else {
                back = true;
            }
        }
        String why = state.writeProblems(scratch);
        if (why != null) return failed(c, why);
        for (String k : changed) state.set(k, scratch.get(k));
        return new Outcome(true, close, back, nav, List.copyOf(calls), c.warnings(), navParameters);
    }

    private Outcome failed(EvalContext c, String reason) {
        c.warn(reason + "，这组 " + (input ? "@input" : "@click") + " 一条都不执行");
        return new Outcome(false, false, false, null, List.of(), c.warnings());
    }
}
