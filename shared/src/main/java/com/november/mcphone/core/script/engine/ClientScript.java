package com.november.mcphone.core.script.engine;

import com.november.mcphone.core.script.layout.StateRules;
import com.november.mcphone.core.script.layout.UiState;
import com.november.mcphone.core.script.http.HttpRequests;
import com.november.mcphone.core.script.server.store.LocalStore;
import com.november.mcphone.core.script.server.store.StoreQuota;
import com.november.mcphone.internal.rhino.javascript.Callable;
import com.november.mcphone.internal.rhino.javascript.CompilerEnvirons;
import com.november.mcphone.internal.rhino.javascript.Context;
import com.november.mcphone.internal.rhino.javascript.LambdaFunction;
import com.november.mcphone.internal.rhino.javascript.NativeArray;
import com.november.mcphone.internal.rhino.javascript.NativeObject;
import com.november.mcphone.internal.rhino.javascript.Node;
import com.november.mcphone.internal.rhino.javascript.Parser;
import com.november.mcphone.internal.rhino.javascript.RhinoException;
import com.november.mcphone.internal.rhino.javascript.Script;
import com.november.mcphone.internal.rhino.javascript.Scriptable;
import com.november.mcphone.internal.rhino.javascript.ScriptableObject;
import com.november.mcphone.internal.rhino.javascript.ast.AstNode;
import com.november.mcphone.internal.rhino.javascript.ast.AstRoot;
import com.november.mcphone.internal.rhino.javascript.ast.FunctionNode;
import com.november.mcphone.internal.rhino.javascript.ast.Name;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Literal initial state plus page-local functions. Compilation never executes author code. */
public final class ClientScript {
    public static final int MAX_SOURCE = 64 * 1024;
    public static final int MAX_FUNCTIONS = 16;
    public static final int MAX_PARAMETERS = 4;
    private static final Set<String> RESERVED = Set.of("state", "ctx", "backend", "close", "back", "nav", "call");

    private final Map<String, Object> initial;
    private final Map<String, Integer> functions;
    private final Script code;
    private final int lineOffset;
    private final ScriptBudget budget = ScriptBudget.client();
    private ScriptableObject globals;
    private Callable ownSymbols;

    private ClientScript(Map<String, Object> initial, Map<String, Integer> functions, Script code, int lineOffset) {
        this.initial = initial;
        this.functions = functions;
        this.code = code;
        this.lineOffset = lineOffset;
    }

    public static ClientScript compile(String source, int lineOffset, Function<String, Map<String, Object>> parseInitial) {
        RuntimeException literalError;
        try {
            return new ClientScript(parseInitial.apply(source), Map.of(), null, lineOffset);
        } catch (RuntimeException e) {
            literalError = e;
        }
        // Keep the established diagnostics for literal-only pages. The AST decides actual declarations.
        if (!source.contains("function")) throw literalError;
        if (source.length() > MAX_SOURCE) throw bad(1, "包含函数的 <script> 最多 64 KiB 字符");
        CompilerEnvirons env = new CompilerEnvirons();
        env.setLanguageVersion(Context.VERSION_ES6);
        AstRoot root;
        try {
            root = new Parser(env).parse(source, "<script>", 1);
        } catch (RuntimeException | StackOverflowError e) {
            throw bad(e instanceof RhinoException rhino ? rhino.lineNumber() : 1, "JavaScript 语法错误或解析深度超限");
        }
        char[] literals = source.toCharArray();
        char[] methods = source.toCharArray();
        Map<String, Integer> names = new LinkedHashMap<>();
        for (Node node : root) {
            AstNode ast = (AstNode) node;
            if (ast instanceof FunctionNode fn && fn.getFunctionType() == FunctionNode.FUNCTION_STATEMENT) {
                String name = fn.getName();
                if (!StateRules.KEY.matcher(name).matches() || RESERVED.contains(name)
                        || ScriptSandbox.ALLOWED_GLOBALS.contains(name)) {
                    throw bad(fn.getLineno(), "函数名不合法或占用了保留名称");
                }
                if (fn.isGenerator() || fn.hasRestParameter() || fn.getDefaultParams() != null
                        || fn.getParams().size() > MAX_PARAMETERS) {
                    throw bad(fn.getLineno(), "只接受普通函数和最多 4 个简单形参，不支持默认参数或剩余参数");
                }
                Set<String> parameters = new LinkedHashSet<>();
                for (AstNode parameter : fn.getParams()) {
                    if (!(parameter instanceof Name id) || !StateRules.KEY.matcher(id.getIdentifier()).matches()
                            || RESERVED.contains(id.getIdentifier()) || ScriptSandbox.ALLOWED_GLOBALS.contains(id.getIdentifier())
                            || "arguments".equals(id.getIdentifier()) || !parameters.add(id.getIdentifier())) {
                        throw bad(parameter.getLineno(), "形参必须是合法且不重复的简单名称，不能占用宿主或内建名称");
                    }
                }
                if ("onInit".equals(name) && !parameters.isEmpty()) throw bad(fn.getLineno(), "onInit 必须是无参函数");
                if (names.putIfAbsent(name, parameters.size()) != null) throw bad(fn.getLineno(), "函数 '" + name + "' 重复声明");
                if (names.size() > MAX_FUNCTIONS) throw bad(fn.getLineno(), "每页最多 16 个顶层函数");
                blank(literals, ast);
            } else {
                blank(methods, ast);
            }
        }
        if (names.isEmpty()) throw literalError;
        // All non-function top-level statements must still be the original literal state declaration.
        Map<String, Object> initial = parseInitial.apply(new String(literals));
        ScriptBudget compiler = ScriptBudget.client();
        try (Context cx = compiler.enterContext()) {
            Script code = cx.compileString(new String(methods), "<script>", 1, null);
            return new ClientScript(initial, Collections.unmodifiableMap(names), code, lineOffset);
        } catch (RuntimeException | StackOverflowError e) {
            throw bad(e instanceof RhinoException rhino ? rhino.lineNumber() : 1, "JavaScript 编译失败或编译深度超限");
        }
    }

    private static void blank(char[] source, AstNode node) {
        int end = node.getAbsolutePosition() + node.getLength();
        for (int i = node.getAbsolutePosition(); i < end; i++) {
            if (source[i] != '\n' && source[i] != '\r') source[i] = ' ';
        }
    }

    private static Syntax bad(int line, String why) {
        return new Syntax(Math.max(1, line), why);
    }

    public Map<String, Object> initialState() { return initial; }
    public boolean declares(String name) { return functions.containsKey(name); }
    public int arity(String name) { return functions.getOrDefault(name, -1); }

    /** A fresh scope and data snapshot per call. Only validated plain state crosses back to the UI. */
    public synchronized Map<String, Object> invoke(String name, Map<String, Object> values, UiState state) {
        try (LocalStore.Transaction storage = new LocalStore.Transaction(null)) {
            return invoke(name, values, state, storage);
        }
    }

    public synchronized Map<String, Object> invoke(String name, Map<String, Object> values, UiState state,
                                                   LocalStore.Transaction storage) {
        return invoke(name, List.of(), values, state, storage);
    }

    /** Template arguments cross as bounded primitives, never wrapped Java objects or state containers. */
    public synchronized Map<String, Object> invoke(String name, List<?> arguments, Map<String, Object> values,
                                                   UiState state, LocalStore.Transaction storage) {
        return invoke(name, arguments, values, state, storage, null);
    }

    public synchronized Map<String, Object> invoke(String name, List<?> arguments, Map<String, Object> values,
                                                   UiState state, LocalStore.Transaction storage, HttpRequests requests) {
        return invoke(name, arguments, values, state, storage, requests, state.stringLimit());
    }

    /** Only the HTTP host can pass the bounded response body beyond the normal template string limit. */
    public synchronized Map<String, Object> respond(HttpRequests.Request request, HttpRequests.Result response,
                                                    UiState state, LocalStore.Transaction storage, HttpRequests requests) {
        return invoke(request.callback(), List.of(response.status(), response.body(), response.error()),
                state.values(), state, storage, requests, HttpRequests.MAX_BODY_BYTES);
    }

    private Map<String, Object> invoke(String name, List<?> arguments, Map<String, Object> values,
                                       UiState state, LocalStore.Transaction storage, HttpRequests requests, int argumentLimit) {
        if (!declares(name)) throw new Failure("没有声明客户端函数 '" + name + "'");
        if (arguments.size() != arity(name)) throw new Failure("客户端函数 '" + name + "' 需要 " + arity(name) + " 个参数");
        Object[] args = new Object[arguments.size()];
        for (int i = 0; i < args.length; i++) {
            Object value = arguments.get(i);
            if (!(value instanceof String || value instanceof Integer || value instanceof Boolean)) {
                throw new Failure("客户端函数第 " + (i + 1) + " 个参数必须是字符串、32 位整数或布尔值");
            }
            if (value instanceof String text && text.length() > argumentLimit) {
                throw new Failure("客户端函数字符串参数最多 " + argumentLimit + " 个 UTF-16 代码单元");
            }
            args[i] = value;
        }
        try (Context cx = budget.enterContext()) {
            // Sealing properties does not freeze every nested object or mutable native slot.
            // A fresh builtin realm prevents data or handles surviving into another login's call.
            globals = ScriptSandbox.harden(cx);
            globals.sealObject();
            Scriptable object = (Scriptable) globals.get("Object", globals);
            ownSymbols = (Callable) object.get("getOwnPropertySymbols", object);
            HostFn.resetDepth();
            budget.begin();
            try {
                ScriptableObject scope = (ScriptableObject) cx.newObject(globals);
                scope.setPrototype(globals);
                scope.setParentScope(null);
                Object snapshot = toJs(cx, scope, values);
                ScriptableObject.defineProperty(scope, "state", snapshot, ScriptableObject.EMPTY);
                installContext(cx, scope, storage, requests, state);
                code.exec(cx, scope); // Only function declarations survived the compile-time AST pass.
                Object fn = scope.get(name, scope);
                if (!(fn instanceof Callable callable)) throw new Failure("客户端函数不可调用");
                callable.call(cx, scope, scope, args);
                noAccessor(scope, "state");
                if (scope.get("state", scope) != snapshot) throw new Failure("不能替换或删除 state，只能修改其中的字段");
                Object result = fromJs(cx, snapshot, 0, state.stringLimit());
                if (!(result instanceof Map<?, ?> map) || !map.keySet().equals(values.keySet())) {
                    throw new Failure("不能添加或删除 state 字段");
                }
                Map<String, Object> next = new LinkedHashMap<>();
                for (String key : values.keySet()) {
                    Object value = map.get(key);
                    String why = state.writeProblem(key, value);
                    if (why != null) throw new Failure(why);
                    next.put(key, value);
                }
                String why = state.writeProblems(next);
                if (why != null) throw new Failure(why);
                return next;
            } finally {
                budget.end();
                HostFn.resetDepth();
            }
        } catch (ScriptAbort e) {
            throw new Failure(e.reason() == ScriptAbort.Reason.HOST ? "客户端函数操作失败（" + e.getMessage() + "）"
                    : "客户端函数执行超限（" + e.reason() + "）");
        } catch (Failure failure) {
            throw failure;
        } catch (RuntimeException e) {
            // Do not stringify thrown script objects or log unbounded user-controlled error messages.
            int line = e instanceof RhinoException rhino ? Math.max(1, rhino.lineNumber()) + lineOffset : 0;
            throw new Failure("客户端函数执行失败" + (line > 0 ? "（script 第 " + line + " 行）" : ""));
        } finally {
            globals = null;
            ownSymbols = null;
        }
    }

    private void installContext(Context cx, ScriptableObject scope, LocalStore.Transaction storage, HttpRequests requests,
                                UiState state) {
        ScriptableObject store = HostFn.obj(cx, scope);
        storeFunction(store, scope, "getString", 2, (c, s, args) -> {
            if (args.length < 1 || args.length > 2) throw HostError.invalid("getString 需要键名和可选的默认字符串");
            String key = HostFn.str(args, 0, "getString");
            String fallback = args.length == 1 ? "" : HostFn.str(args, 1, "getString");
            return storeCall(() -> storage.getString(key, fallback));
        });
        storeFunction(store, scope, "setString", 2, (c, s, args) -> {
            if (args.length != 2) throw HostError.invalid("setString 需要键名和字符串值");
            String key = HostFn.str(args, 0, "setString");
            String value = HostFn.str(args, 1, "setString");
            storeCall(() -> { storage.setString(key, value); return ""; });
            return com.november.mcphone.internal.rhino.javascript.Undefined.instance;
        });
        store.sealObject();
        ScriptableObject ctx = HostFn.obj(cx, scope);
        ScriptableObject params = HostFn.obj(cx, scope);
        for (var entry : state.parameters().values().entrySet()) {
            ScriptableObject.defineProperty(params, entry.getKey(), entry.getValue(),
                    ScriptableObject.READONLY | ScriptableObject.PERMANENT);
        }
        params.sealObject();
        ScriptableObject.defineProperty(ctx, "params", params, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
        ScriptableObject.defineProperty(ctx, "store", store, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
        ScriptableObject http = HostFn.obj(cx, scope);
        storeFunction(http, scope, "request", 2, (c, s, args) -> {
            if (args.length != 2) throw HostError.invalid("request 需要请求选项和回调函数名");
            if (requests == null) throw HostError.invalid("当前页面没有可用的网络请求宿主");
            String callback = HostFn.str(args, 1, "request");
            if (arity(callback) != 3 || "onInit".equals(callback)) throw HostError.invalid("HTTP 回调须为本页声明的三参数函数");
            try { return requests.enqueue(readRequest(c, args[0], callback)); }
            catch (IllegalArgumentException e) { throw HostError.invalid(e.getMessage()); }
            catch (Failure e) { throw HostError.invalid(e.getMessage()); }
        });
        storeFunction(http, scope, "cancel", 1, (c, s, args) -> {
            if (args.length != 1 || !(args[0] instanceof Number number) || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() != Math.rint(number.doubleValue()) || number.doubleValue() < 0
                    || number.doubleValue() > Integer.MAX_VALUE) throw HostError.invalid("cancel 需要非负的 32 位整数请求编号");
            if (requests == null) throw HostError.invalid("当前页面没有可用的网络请求宿主");
            try { requests.cancel(number.intValue()); }
            catch (IllegalArgumentException e) { throw HostError.invalid(e.getMessage()); }
            return com.november.mcphone.internal.rhino.javascript.Undefined.instance;
        });
        http.sealObject();
        ScriptableObject.defineProperty(ctx, "http", http, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
        ctx.sealObject();
        ScriptableObject.defineProperty(scope, "ctx", ctx, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
    }

    private HttpRequests.Request readRequest(Context cx, Object value, String callback) {
        Map<String, Object> options = ownFields(cx, value, 5);
        if (!Set.of("url", "method", "headers", "body", "timeoutMs").containsAll(options.keySet())) {
            throw HostError.invalid("未知的 HTTP 请求选项");
        }
        String url = requestString(options.get("url"), "url", 2048);
        String method = requestString(options.getOrDefault("method", "GET"), "method", 4);
        String body = requestString(options.getOrDefault("body", ""), "body", HttpRequests.MAX_BODY_BYTES);
        Object rawTimeout = options.getOrDefault("timeoutMs", HttpRequests.DEFAULT_TIMEOUT_MS);
        if (!(rawTimeout instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue()) || number.doubleValue() < 1000
                || number.doubleValue() > 120_000) throw HostError.invalid("timeoutMs 需为 1000–120000 的整数");
        Map<String, String> headers = new LinkedHashMap<>();
        if (options.containsKey("headers")) {
            for (var field : ownFields(cx, options.get("headers"), 16).entrySet()) {
                headers.put(field.getKey(), requestString(field.getValue(), "header", 4096));
            }
        }
        return new HttpRequests.Request(url, method, headers, body, number.intValue(), callback);
    }

    private Map<String, Object> ownFields(Context cx, Object value, int limit) {
        if (!(value instanceof NativeObject object)) throw HostError.invalid("请求选项和请求头必须是普通对象");
        noSymbols(cx, object);
        Object[] ids = object.getAllIds();
        if (ids.length > limit) throw HostError.invalid("HTTP 对象字段过多");
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Object id : ids) {
            if (!(id instanceof String key)) throw HostError.invalid("HTTP 对象字段名必须是字符串");
            noAccessor(object, key);
            fields.put(key, object.get(key, object));
        }
        return fields;
    }

    private static String requestString(Object value, String field, int limit) {
        if (!(value instanceof CharSequence text) || text.length() > limit) throw HostError.invalid(field + " 类型或长度不合法");
        return text.toString();
    }

    private static void storeFunction(ScriptableObject target, Scriptable scope, String name, int arity, HostFn.Body body) {
        Callable checked = HostFn.of(scope, name, arity, body);
        LambdaFunction entry = new LambdaFunction(scope, name, arity, (cx, sc, self, args) -> {
            try { return checked.call(cx, sc, self, args); }
            catch (HostError e) { throw new ScriptAbort(ScriptAbort.Reason.HOST, e.detail()); }
        });
        entry.sealObject();
        ScriptableObject.putProperty(target, name, entry);
    }

    private static String storeCall(java.util.function.Supplier<String> operation) {
        try { return operation.get(); }
        catch (StoreQuota.QuotaExceeded e) { throw HostError.invalid("本地存储超出配额"); }
        catch (IllegalArgumentException e) { throw HostError.invalid(e.getMessage()); }
        catch (RuntimeException e) { throw HostError.invalid("无法读取本地存储，原文件已保留；账号改变时请重新打开 App"); }
    }

    private static Object toJs(Context cx, Scriptable scope, Object value) {
        if (value instanceof Map<?, ?> map) {
            ScriptableObject obj = (ScriptableObject) cx.newObject(scope);
            for (Map.Entry<?, ?> e : map.entrySet()) {
                ScriptableObject.defineProperty(obj, (String) e.getKey(), toJs(cx, scope, e.getValue()), ScriptableObject.EMPTY);
            }
            return obj;
        }
        if (value instanceof List<?> list) {
            Object[] items = new Object[list.size()];
            for (int i = 0; i < items.length; i++) items[i] = toJs(cx, scope, list.get(i));
            return cx.newArray(scope, items);
        }
        return value; // Only the existing UiState primitive types, never a wrapped Java object.
    }

    private Object fromJs(Context cx, Object value, int depth, int stringLimit) {
        if (value instanceof Boolean) return value;
        if (value instanceof CharSequence text) {
            if (text.length() > stringLimit) throw new Failure("state 字符串最多 " + stringLimit + " 个 UTF-16 代码单元");
            return text.toString();
        }
        if (value instanceof Number number) {
            double n = number.doubleValue();
            if (!Double.isFinite(n) || n != Math.rint(n) || n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) {
                throw new Failure("state 数值必须是 32 位整数");
            }
            return (int) n;
        }
        if (depth > StateRules.MAX_DEPTH) throw new Failure("state 数据嵌套过深");
        if (value instanceof NativeArray array) {
            if (array.getLength() > StateRules.MAX_ARRAY) throw new Failure("state 数组最多 32 项");
            noSymbols(cx, array);
            int size = (int) array.getLength();
            for (Object id : array.getAllIds()) {
                if (id.equals("length")) continue;
                if (!(id instanceof Integer index) || index < 0 || index >= size) throw new Failure("state 数组不能带额外属性");
            }
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                noAccessor(array, i);
                if (!array.has(i, array)) throw new Failure("state 数组不能包含空位");
                out.add(fromJs(cx, array.get(i, array), depth + 1, stringLimit));
            }
            return out;
        }
        if (value instanceof NativeObject object) {
            noSymbols(cx, object);
            Object[] ids = object.getAllIds();
            if (ids.length > StateRules.MAX_OBJECT_KEYS) throw new Failure("state 对象最多 16 个字段");
            Map<String, Object> out = new LinkedHashMap<>();
            for (Object id : ids) {
                if (!(id instanceof String key) || !StateRules.KEY.matcher(key).matches()) throw new Failure("state 对象字段名不合法");
                noAccessor(object, key);
                out.put(key, fromJs(cx, object.get(key, object), depth + 1, stringLimit));
            }
            return out;
        }
        throw new Failure("state 只能保存整数、布尔、字符串、普通数组和普通对象");
    }

    private void noSymbols(Context cx, ScriptableObject object) {
        // getAllIds excludes symbols. Call the sealed native builtin, never a script-supplied method.
        NativeArray keys = (NativeArray) ownSymbols.call(cx, globals, globals, new Object[]{object});
        if (keys.getLength() != 0) throw new Failure("state 不能包含 Symbol 字段");
    }

    private static void noAccessor(ScriptableObject object, Object key) {
        String name = key instanceof String s ? s : null;
        int index = key instanceof Integer i ? i : 0;
        if (object.getGetterOrSetter(name, index, false) instanceof Callable
                || object.getGetterOrSetter(name, index, true) instanceof Callable) {
            throw new Failure("state 不能包含 getter 或 setter");
        }
    }

    /** Controlled failure consumed by the existing atomic click transaction. */
    public static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Failure(String message) { super(message); }
    }

    /** Engine-neutral diagnostic; the SFC compiler supplies the file and block offset. */
    public static final class Syntax extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int line;
        private Syntax(int line, String message) { super(message); this.line = line; }
        public int line() { return line; }
    }
}
