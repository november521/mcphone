package com.november.mcphone.core.script.engine;

import com.november.mcphone.core.script.JsonValues;
import com.november.mcphone.core.script.layout.UiState;
import com.november.mcphone.core.script.sfc.FrontendProgram;
import org.mozilla.javascript.*;

import java.util.*;
import java.util.function.Consumer;

/** 页面存活期间的前端引擎。所有入口和回调都过预算；宿主效果先暂存，求值与驻留检查成功后才发送。 */
public final class FrontendRuntime implements AutoCloseable {
    public interface Bridge {
        void call(String action, String params, Consumer<Map<String, Object>> callback);
        default void fetch(String url, String authorization, int offset, Consumer<Map<String, Object>> callback) {
            callback.accept(Map.of("status", "UNAVAILABLE", "text", ""));
        }
        default void image(String url, String authorization, Consumer<Map<String, Object>> callback) {
            callback.accept(Map.of("status", "UNAVAILABLE"));
        }
        default void imageBytes(String base64, Consumer<Map<String, Object>> callback) {
            callback.accept(Map.of("status", "UNAVAILABLE"));
        }
        default void sealed(boolean write, String key, String text, Consumer<Map<String, Object>> callback) {
            callback.accept(Map.of("ok", false, "message", "保险箱不可用"));
        }
        default Map<String, Object> backend() { return Map.of("available", false, "actions", List.of(), "serverName", ""); }
        default boolean allowRetained(long bytes) { return true; }
        default int textInputLimit(String key) { return 0; }
        void toast(String text);
        void navigate(String page);
        void back();
        void closePage();
        void failed(String text);
    }

    private record Event(Function fn, List<Object> arguments) { }
    private final FrontendProgram program;
    private final UiState state;
    private final Bridge bridge;
    private final String file;
    private final ScriptBudget budget = ScriptBudget.client();
    private final Map<Integer, Function> callbacks = new LinkedHashMap<>();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private List<Runnable> effects;
    private HostScope scope;
    private int sequence;
    private long retainedBytes, baseRetained;
    private boolean processing, closed, failed;

    private static final class HostScope extends NativeObject {
        private Object backend;
        @Override public Object get(String name, Scriptable start) {
            return name.equals("backend") ? backend : super.get(name, start);
        }
    }

    public FrontendRuntime(FrontendProgram program, UiState state, String file, Bridge bridge) {
        this.program = program; this.state = state; this.file = file; this.bridge = bridge;
    }

    public boolean failed() { return failed; }
    public int pending() { return callbacks.size(); }
    public long retainedBytes() { return retainedBytes; }

    public void invoke(String name, List<Object> arguments) {
        if (closed || failed) return;
        if (!program.handlers().contains(name)) { fail("前端函数不存在：" + name); return; }
        if (scope == null && !initialize()) return;
        Object fn = scope.get(name, scope);
        if (!(fn instanceof Function function)) { fail("前端函数无效：" + name); return; }
        enqueue(new Event(function, arguments));
    }

    private boolean initialize() {
        budget.begin(); HostFn.resetDepth();
        try (Context cx = budget.enterContext()) {
            ScriptableObject standard = ScriptSandbox.harden(cx);
            scope = new HostScope();
            scope.setPrototype(standard); scope.setParentScope(null);
            scope.backend = FrontendValues.write(cx, scope, bridge.backend(), true);
            scope.defineProperty("backend", (Object) null, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
            scope.defineProperty("state", FrontendValues.write(cx, scope, state.values(), false), ScriptableObject.READONLY | ScriptableObject.PERMANENT);
            install(cx);
            if (program.compiled() != null) program.compiled().exec(cx, scope);
            for (String name : program.handlers()) {
                Object value = scope.get(name, scope);
                if (!(value instanceof ScriptableObject function)) throw HostError.invalid("前端函数无效");
                Object prototype = function.get("prototype", function);
                if (prototype instanceof ScriptableObject object) object.sealObject();
                function.sealObject();
                scope.setAttributes(name, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
            }
            // 标准对象也通过独立的密封作用域访问，不能写进这一层；globalThis 指向标准作用域，需一并冻结绑定。
            for (Object id : standard.getAllIds()) if (id instanceof String name)
                standard.setAttributes(name, ScriptableObject.READONLY | ScriptableObject.PERMANENT | ScriptableObject.DONTENUM);
            standard.sealObject();
            scope.sealObject();
            return true;
        } catch (ScriptAbort | RuntimeException error) { fail(message(error)); return false; }
        finally { budget.end(); HostFn.resetDepth(); }
    }

    private void install(Context cx) {
        ScriptableObject phone = HostFn.obj(cx, scope);
        HostFn.put(phone, scope, "call", 3, (c, s, a) -> {
            String action = HostFn.str(a, 0, "phone.call");
            if (action.isEmpty() || action.length() > 64 || action.chars().anyMatch(Character::isISOControl))
                throw HostError.invalid("动作名须为 1–64 字符且无控制字符");
            Object data = FrontendValues.read(a.length > 1 ? a[1] : null);
            if (!(data instanceof Map<?, ?> map)) throw HostError.invalid("phone.call 的参数须为 JSON 对象");
            @SuppressWarnings("unchecked") String json = JsonValues.encode((Map<String, Object>) map);
            int id = reserve(a, 2);
            if (id != 0) stage(() -> bridge.call(action, json, response -> reply(id, response)));
            return Undefined.instance;
        });
        HostFn.put(phone, scope, "fetch", 3, (c, s, a) -> {
            String url = HostFn.str(a, 0, "phone.fetch");
            Object raw = FrontendValues.read(a.length > 1 ? a[1] : null);
            if (!(raw instanceof Map<?, ?> options) || !Set.of("authorization", "offset").containsAll(options.keySet()))
                throw HostError.invalid("phone.fetch 选项只接受 authorization 和 offset");
            Object token = options.containsKey("authorization") ? options.get("authorization") : "";
            Object offset = options.containsKey("offset") ? options.get("offset") : 0;
            if (!(token instanceof String authorization) || !(offset instanceof Integer from)) throw HostError.invalid("网络选项类型错误");
            int id = reserve(a, 2);
            if (id != 0) stage(() -> bridge.fetch(url, authorization, from, response -> networkReply(id, response)));
            return Undefined.instance;
        });
        HostFn.put(phone, scope, "image", 3, (c, s, a) -> {
            String url = HostFn.str(a, 0, "phone.image");
            Object raw = FrontendValues.read(a.length > 1 ? a[1] : null);
            if (!(raw instanceof Map<?, ?> options) || !Set.of("authorization").containsAll(options.keySet()))
                throw HostError.invalid("phone.image 选项只接受 authorization");
            Object token = options.containsKey("authorization") ? options.get("authorization") : "";
            if (!(token instanceof String authorization)) throw HostError.invalid("网络选项类型错误");
            int id = reserve(a, 2);
            if (id != 0) stage(() -> bridge.image(url, authorization, response -> networkReply(id, response)));
            return Undefined.instance;
        });
        HostFn.put(phone, scope, "imageBytes", 2, (c, s, a) -> {
            String base64 = HostFn.str(a, 0, "phone.imageBytes");
            if (base64.length() > 87384) throw HostError.invalid("PNG 超过 64 KiB");
            int id = reserve(a, 1);
            if (id != 0) stage(() -> bridge.imageBytes(base64, response -> reply(id, response)));
            return Undefined.instance;
        });
        ScriptableObject sealed = HostFn.obj(cx, scope);
        for (boolean write : new boolean[]{false, true}) HostFn.put(sealed, scope, write ? "put" : "get", write ? 3 : 2, (c, s, a) -> {
            String key = HostFn.str(a, 0, "phone.sealed");
            if (!key.matches("[A-Za-z0-9_.-]{1,64}")) throw HostError.invalid("保险箱键无效");
            String text = write ? HostFn.str(a, 1, "phone.sealed.put") : "";
            if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2048) throw HostError.invalid("保险箱明文超过 2 KiB");
            int id = reserve(a, write ? 2 : 1);
            if (id != 0) stage(() -> bridge.sealed(write, key, text, response -> reply(id, response)));
            return Undefined.instance;
        });
        sealed.sealObject(); phone.defineProperty("sealed", sealed, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
        phone.sealObject(); scope.defineProperty("phone", phone, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
        host("toast", 1, (c, s, a) -> { String text = HostFn.str(a, 0, "toast"); if (text.length() > 256) throw HostError.invalid("提示最多 256 字符"); stage(() -> bridge.toast(text)); return Undefined.instance; });
        host("nav", 1, (c, s, a) -> { String page = HostFn.str(a, 0, "nav"); if (page.length() > 64) throw HostError.invalid("页面名超长"); stage(() -> bridge.navigate(page)); return Undefined.instance; });
        host("back", 0, (c, s, a) -> { stage(bridge::back); return Undefined.instance; });
        host("close", 0, (c, s, a) -> { stage(bridge::closePage); return Undefined.instance; });
    }

    private void host(String name, int arity, HostFn.Body body) {
        scope.defineProperty(name, HostFn.of(scope, name, arity, body), ScriptableObject.READONLY | ScriptableObject.PERMANENT);
    }

    private int reserve(Object[] args, int index) {
        if (args.length <= index || !(args[index] instanceof Function fn)) throw HostError.invalid("异步桥须提供回调函数");
        if (callbacks.size() >= 4) {
            if (events.size() >= 8) throw new ScriptAbort(ScriptAbort.Reason.SIZE, "同步回调过多");
            events.add(new Event(fn, List.of(Map.of("ok", false, "code", "IN_PROGRESS", "data", Map.of(), "message", "已有四个未完成请求"))));
            return 0;
        }
        int id = ++sequence; callbacks.put(id, fn); return id;
    }

    private void stage(Runnable effect) {
        if (effects == null || effects.size() >= 32) throw new ScriptAbort(ScriptAbort.Reason.SIZE, "单次前端调用最多 32 个宿主操作");
        effects.add(effect);
    }

    private void reply(int id, Map<String, Object> response) {
        Function fn = callbacks.remove(id);
        if (fn != null && !closed && !failed) enqueue(new Event(fn, List.of(response)));
    }
    private void networkReply(int id, Map<String, Object> response) {
        // PENDING 只表示已出队，终态回来才消费闭包；同键并发的 BUSY 仍立即归还名额。
        if (!"PENDING".equals(response.get("status"))) reply(id, response);
    }

    private void enqueue(Event event) {
        if (events.size() >= 8) { fail("前端回调队列超限"); return; }
        events.add(event);
        if (processing) return;
        processing = true;
        long deadline = System.nanoTime() + ScriptBudget.CLIENT_WALL_NANOS;
        int count = 0;
        try {
            while (!events.isEmpty() && !closed && !failed) {
                if (++count > 8 || System.nanoTime() >= deadline) { fail("连续前端回调超过预算"); break; }
                run(events.removeFirst(), deadline);
            }
        } finally { processing = false; }
    }

    private void run(Event event, long deadline) {
        budget.begin(Math.max(1, deadline - System.nanoTime())); HostFn.resetDepth(); effects = new ArrayList<>();
        List<Runnable> committed = List.of();
        try (Context cx = budget.enterContext()) {
            ScriptableObject jsState = (ScriptableObject) scope.get("state", scope);
            // 保持 state 对象身份，同时把输入框或旧式点击的改动同步过来。
            for (Object key : jsState.getAllIds()) if (key instanceof String name) jsState.delete(name);
            for (var entry : state.values().entrySet()) jsState.put(entry.getKey(), jsState, FrontendValues.write(cx, scope, entry.getValue(), false));
            scope.backend = FrontendValues.write(cx, scope, bridge.backend(), true);
            Object[] args = event.arguments().stream().map(value -> FrontendValues.write(cx, scope, value, true)).toArray();
            event.fn().call(cx, scope, scope, args);
            Object converted = FrontendValues.read(jsState);
            @SuppressWarnings("unchecked") Map<String, Object> changed = (Map<String, Object>) converted;
            if (!changed.keySet().equals(state.values().keySet())) throw HostError.invalid("state 只能使用初始声明的键");
            for (var entry : changed.entrySet()) if (!Objects.equals(state.get(entry.getKey()), entry.getValue())) {
                String why = state.writeProblem(entry.getKey(), entry.getValue());
                int limit=bridge.textInputLimit(entry.getKey());
                if(state.get(entry.getKey()) instanceof String&&entry.getValue() instanceof String text&&limit>0
                        &&text.codePointCount(0,text.length())<=Math.min(limit,4096)&&text.equals(com.november.mcphone.core.script.layout.TextInputBuffer.clean(text,4096)))why=null;
                if (why != null) throw HostError.invalid(why);
            }
            FrontendRetention retention = new FrontendRetention(scope);
            for (String name : program.handlers()) retention.check(scope.get(name, scope));
            retention.check(jsState);
            long base = retention.bytes();
            for (Function callback : callbacks.values()) retention.check(callback);
            for (Event waiting : events) retention.check(waiting.fn());
            if (!bridge.allowRetained(retention.bytes())) throw new ScriptAbort(ScriptAbort.Reason.RETAINED, "同一 App 的全部页面驻留超过 1 MiB");
            if (ScriptBudget.wallLeftNanos() <= 0) throw new ScriptAbort(ScriptAbort.Reason.WALL_CLOCK, "前端调用超过墙钟预算");
            for (var entry : changed.entrySet()) state.set(entry.getKey(), entry.getValue());
            retainedBytes = retention.bytes(); baseRetained = base;
            committed = List.copyOf(effects);
        } catch (ScriptAbort | RuntimeException error) { fail(message(error)); }
        finally { effects = null; budget.end(); HostFn.resetDepth(); }
        for (Runnable effect : committed) {
            if (closed || failed) break;
            try { effect.run(); } catch (RuntimeException error) { fail("前端宿主操作失败，已发出的请求请核对服务端记录"); break; }
        }
    }

    private String message(Throwable error) {
        if (error instanceof RhinoException rhino)
            return file + ":" + Math.max(program.firstLine(), rhino.lineNumber()) + ":" + Math.max(1, rhino.columnNumber()) + " " + rhino.details();
        return file + " " + error.getMessage();
    }

    private void fail(String reason) {
        if (failed || closed) return;
        failed = true; callbacks.clear(); events.clear(); scope = null; retainedBytes = 0;
        bridge.failed(reason.length() > 512 ? reason.substring(0, 512) : reason);
    }

    @Override public void close() { closed = true; callbacks.clear(); events.clear(); effects = null; scope = null; retainedBytes = 0; }

    /** 策略撤销/断线时丢弃闭包，不重试仍可能在服务端落地的请求。 */
    public void discardCallbacks() { callbacks.clear(); events.clear(); retainedBytes = baseRetained; }
}
