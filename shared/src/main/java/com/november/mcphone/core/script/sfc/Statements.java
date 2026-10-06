package com.november.mcphone.core.script.sfc;

import com.november.mcphone.core.script.layout.UiState;
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
 * {@code @click} 的值（施工方案 §9.6）：赋值、自增减、内建 close() / back() / nav(...) / call('动作')，
 * 用 ; 连接最多 4 条。它不是表达式语言的一部分，所以 {@code {{ count++ }}} 报的是不能赋值。
 *
 * <p>按书写顺序在一份副本上执行，全部成功才写回 state：半执行的状态比不执行更难排查。
 */
public final class Statements {

    public static final int MAX_STATEMENTS = 4;

    /** {@code call(...)} 里动作 id 的长度上限，与 {@code ScriptProtocol.ID_MAX} 一致（那份是线格式，这里是编译期）。 */
    public static final int MAX_ACTION_LEN = 64;

    private static final String SHAPES =
            "@click 只能写 x = 表达式、x++、x--、close()、back()、nav('页面')、call('动作')";

    sealed interface Stmt permits Assign, Step, Close, Back, Nav, Call, Fetch, Sealed, Handler {
    }

    record Assign(String key, Expr.Compiled value) implements Stmt {
    }

    record Step(String key, int delta) implements Stmt {
    }

    record Close() implements Stmt {
    }

    record Back() implements Stmt {
    }

    record Nav(Expr.Compiled page) implements Stmt {
    }

    /** 参数显式列出 state 键；取这条语句执行时的副本，不把整个页面状态隐式发给服务器。 */
    record Call(String actionId, List<String> keys) implements Stmt {
    }
    record Fetch(String url, String authorizationKey, String offsetKey) implements Stmt { }
    record Sealed(boolean write,String key,String stateKey) implements Stmt { }
    record Handler(String name, List<Expr.Compiled> arguments) implements Stmt { }

    /**
     * 一次点击的结果。applied 为 false 时 state 一个都没改，close / back / nav / call 也都不算数。
     * close、back、nav 同时出现时由页面决定先后，这里只如实报出写了哪些。
     * {@code calls} 是要宿主发出去的动作 id，按书写顺序。
     */
    public record Outcome(boolean applied, boolean close, boolean back, String nav,
                          List<String> calls, List<String> warnings, List<CallRequest> requests, List<FetchRequest> networkRequests,List<SealedRequest> sealedRequests,
                          List<HandlerRequest> handlers) {
        public Outcome(boolean applied, boolean close, boolean back, String nav,
                       List<String> calls, List<String> warnings, List<CallRequest> requests,
                       List<FetchRequest> networkRequests, List<SealedRequest> sealedRequests) {
            this(applied, close, back, nav, calls, warnings, requests, networkRequests, sealedRequests, List.of());
        }
        public Outcome(boolean applied, boolean close, boolean back, String nav,
                       List<String> calls,List<String> warnings,List<CallRequest> requests,List<FetchRequest> networkRequests) {
            this(applied,close,back,nav,calls,warnings,requests,networkRequests,List.of());
        }
        public Outcome(boolean applied, boolean close, boolean back, String nav,
                       List<String> calls, List<String> warnings) {
            this(applied, close, back, nav, calls, warnings,
                    calls.stream().map(id -> new CallRequest(id, "")).toList(), List.of());
        }
    }

    public record CallRequest(String action, String paramsJson) { }
    public record FetchRequest(String url, String authorization, int offset) { }
    public record SealedRequest(boolean write,String key,String stateKey,String plaintext) { }
    public record HandlerRequest(String name, List<Object> arguments) { }

    /** 实例化时绑好 v-for 变量的一条 @click，点击时执行。 */
    public record Bound(Statements statements, List<String> names, List<Object> values,
                        java.util.Map<String, Object> host) {
        public Outcome run(UiState state, String file) {
            return statements.run(state, file, names, values, host);
        }
    }

    private final List<Stmt> list;
    private final int line;

    private Statements(List<Stmt> list, int line) {
        this.list = list;
        this.line = line;
    }

    List<Stmt> list() {
        return list;
    }

    static Statements parse(String src, Scope scope, int line, int col) {
        // 只防超大输入；单条语句的 256 字符在下面按记号量，空白不算
        if (src.length() > MAX_STATEMENTS * ExprParser.MAX_LENGTH * 4) throw SfcError.at(Code.E_EXPR_TOO_LONG, line, col);
        ExprParser p = new ExprParser(src, line, col, false);
        if (p.peek().kind() == 'e') throw ExprParser.syntax(p.peek(), "@click 是空的");
        List<Stmt> out = new ArrayList<>();
        while (p.peek().kind() != 'e') {
            Tok first = p.peek();
            if (out.size() == MAX_STATEMENTS) throw ExprParser.syntax(first, "@click 最多 " + MAX_STATEMENTS + " 条语句");
            if (span(p) > ExprParser.MAX_LENGTH) throw SfcError.at(Code.E_EXPR_TOO_LONG, first.line(), first.col());
            out.add(statement(p, scope, src));
            if (p.at(";")) {
                p.next();
            } else if (p.peek().kind() != 'e') {
                throw ExprParser.syntax(p.peek(), "两条语句之间要用 ; 隔开");
            }
        }
        return new Statements(List.copyOf(out), line);
    }

    private static Stmt statement(ExprParser p, Scope scope, String src) {
        Tok t = p.next();
        if (t.kind() != 'n') throw ExprParser.syntax(t, SHAPES);
        Tok after = p.peek();
        String name = t.text();

        if (ExprParser.is(after, "(")) {
            if (scope.isHandler(name)) {
                p.next();
                List<Expr.Compiled> args = new ArrayList<>();
                if (!p.at(")")) do {
                    if (args.size() == 8) throw ExprParser.syntax(p.peek(), "脚本函数最多 8 个参数");
                    args.add(compiled(p.parse(scope), t, src));
                    if (!p.at(",")) break;
                    p.next();
                } while (true);
                p.expect(")");
                return new Handler(name, List.copyOf(args));
            }
            switch (name) {
                case "close", "back" -> {
                    p.next();
                    p.expect(")");
                    return name.equals("close") ? new Close() : new Back();
                }
                case "nav" -> {
                    p.next();
                    Typed page = p.parse(scope);
                    p.expect(")");
                    return new Nav(compiled(page, t, src));
                }
                case "sealedGet", "sealedPut" -> {
                    p.next();Tok key=p.next();
                    if(key.kind()!='s'||!((String)key.value()).matches("[A-Za-z0-9_.-]{1,64}"))throw ExprParser.syntax(key,"保险箱键必须是 1–64 位固定标识");
                    p.expect(",");Tok state=p.next();
                    if(state.kind()!='s'||!scope.isState((String)state.value()))throw ExprParser.syntax(state,"保险箱必须绑定已声明的字符串 state");
                    p.expect(")");return new Sealed(name.equals("sealedPut"),(String)key.value(),(String)state.value());
                }
                case "fetch" -> {
                    p.next(); Tok url = p.next();
                    if (url.kind() != 's') throw ExprParser.syntax(url, "fetch 的 URL 必须是字符串常量");
                    String address = (String) url.value();
                    try {
                        var uri = java.net.URI.create(address);
                        if (uri.getHost() == null) throw new IllegalArgumentException();
                        com.november.mcphone.core.script.server.SafeFetch.validate(address, Set.of(uri.getHost().toLowerCase(java.util.Locale.ROOT)));
                    } catch (RuntimeException invalid) { throw ExprParser.syntax(url, "fetch 只接受有效的 https:443 URL"); }
                    List<String> keys = new ArrayList<>();
                    while (p.at(",")) {
                        p.next(); Tok key = p.next();
                        if (keys.size() == 2 || key.kind() != 's'
                                || (!((String) key.value()).isEmpty() && !scope.isState((String) key.value())))
                            throw ExprParser.syntax(key, "fetch 只接受凭证、偏移两个 state 键；空串表示不传凭证");
                        keys.add((String) key.value());
                    }
                    p.expect(")"); return new Fetch(address, keys.isEmpty() ? "" : keys.get(0), keys.size()<2 ? "" : keys.get(1));
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
                    List<String> keys = new ArrayList<>();
                    while (p.at(",")) {
                        p.next();
                        Tok key = p.next();
                        if (key.kind() != 's' || !scope.isState((String) key.value()))
                            throw ExprParser.syntax(key, "call 参数要已声明 state 键的字符串");
                        String value = (String) key.value();
                        if (keys.size() == 16 || keys.contains(value))
                            throw ExprParser.syntax(key, "call 最多 16 个不同的 state 键");
                        keys.add(value);
                    }
                    p.expect(")");
                    return new Call(action, List.copyOf(keys));
                }
                default -> throw SfcError.at(Code.E_EXPR_NO_CALLS, t.line(), t.col());
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
                moved.add(new Nav(shift(n.page(), lines)));
            } else if (s instanceof Handler h) {
                moved.add(new Handler(h.name(), h.arguments().stream().map(a -> shift(a, lines)).toList()));
            } else {
                moved.add(s);
            }
        }
        return new Statements(List.copyOf(moved), line + lines);
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
        Map<String, Object> scratch = new LinkedHashMap<>(state.values());
        EvalContext c = new EvalContext(scratch, host, file);
        for (int i = 0; i < names.size(); i++) c.push(names.get(i), values.get(i));
        boolean close = false;
        boolean back = false;
        String nav = null;
        List<String> calls = new ArrayList<>();
        List<CallRequest> requests = new ArrayList<>();
        List<FetchRequest> networkRequests = new ArrayList<>();
        List<SealedRequest> sealedRequests=new ArrayList<>();
        List<HandlerRequest> handlers = new ArrayList<>();
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
                nav = page;
            } else if (s instanceof Fetch fetch) {
                Object token = fetch.authorizationKey().isEmpty() ? "" : scratch.get(fetch.authorizationKey());
                Object offset = fetch.offsetKey().isEmpty() ? 0 : scratch.get(fetch.offsetKey());
                if (!(token instanceof String authorization) || authorization.length() > 2048
                        || authorization.chars().anyMatch(ch -> ch < 32 || ch > 126)
                        || !(offset instanceof Integer from) || from < 0 || from > 262144)
                    return failed(c, "fetch 凭证必须是无控制字符的 ASCII 字符串，偏移必须是 0–262144 的 int");
                networkRequests.add(new FetchRequest(fetch.url(), authorization, from));
            } else if(s instanceof Sealed sealed) {
                Object value=scratch.get(sealed.stateKey());
                if(!(value instanceof String plaintext)||plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2048)return failed(c,"保险箱绑定必须是最多 2 KiB 的字符串");
                sealedRequests.add(new SealedRequest(sealed.write(),sealed.key(),sealed.stateKey(),sealed.write()?plaintext:""));
            } else if (s instanceof Call call) {
                calls.add(call.actionId());
                Map<String, Object> params = new LinkedHashMap<>();
                for (String key : call.keys()) params.put(key, scratch.get(key));
                try {
                    requests.add(new CallRequest(call.actionId(), call.keys().isEmpty() ? ""
                            : com.november.mcphone.core.script.JsonValues.encode(params)));
                } catch (IllegalArgumentException e) { return failed(c, e.getMessage()); }
            } else if (s instanceof Handler handler) {
                List<Object> args = new ArrayList<>();
                for (Expr.Compiled value : handler.arguments()) args.add(value.run(c));
                handlers.add(new HandlerRequest(handler.name(), java.util.Collections.unmodifiableList(args)));
            } else if (s instanceof Close) {
                close = true;
            } else {
                back = true;
            }
        }
        for (String k : changed) state.set(k, scratch.get(k));
        return new Outcome(true, close, back, nav, List.copyOf(calls), c.warnings(), List.copyOf(requests), List.copyOf(networkRequests),List.copyOf(sealedRequests), List.copyOf(handlers));
    }

    private static Outcome failed(EvalContext c, String reason) {
        c.warn(reason + "，这组 @click 一条都不执行");
        return new Outcome(false, false, false, null, List.of(), c.warnings());
    }
}
