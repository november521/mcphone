package com.november.mcphone.core.script.engine;

import org.mozilla.javascript.*;
import java.util.*;

/** 计量回调闭包中的活动作用域，不只计量全局字符串；读取前拒绝 getter，避免审计自己执行作者代码。 */
final class FrontendRetention {
    private final Scriptable root;
    private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private long bytes;
    private int nodes;

    FrontendRetention(Scriptable root) { this.root = root; }

    void check(Object value) { scan(value, 0); }
    long bytes() { return bytes; }

    private void scan(Object value, int depth) {
        if (value == null || value == Undefined.instance || value == Scriptable.NOT_FOUND || value == root) return;
        if (++nodes > 8192 || depth > 64) abort("驻留对象过深或超过 8192 个节点");
        if (value instanceof CharSequence text) { add(2L * text.length()); return; }
        if (value instanceof java.math.BigInteger n) { add(32L + n.bitLength() / 8); return; }
        if (value instanceof Number || value instanceof Boolean) { add(32); return; }
        if (!(value instanceof ScriptableObject object)) abort("不能计量的前端驻留对象");
        ScriptableObject object = (ScriptableObject) value;
        if (!seen.add(object)) return;
        if (object instanceof LambdaFunction) return; // 只允许宿主手工建立的只读桥，不持有作者的数据。
        if (!(object instanceof NativeObject) && !(object instanceof NativeArray) && !(object instanceof JSFunction)
                && !Set.of("Call", "Arguments").contains(object.getClassName()))
            abort("回调不能保留不可计量对象 " + object.getClass().getSimpleName() + "；请转换成普通数据");
        add(128);
        if (object instanceof NativeArray array && array.getLength() > SizeGate.MAX_ARRAY) abort("驻留数组超限");
        Object[] ids = object.getAllIds();
        if (ids.length > 4096) abort("驻留属性数超限");
        for (Object id : ids) {
            if (!(id instanceof Number) && !(id instanceof String)) abort("回调不能保留符号属性");
            FrontendValues.noAccessor(object, id);
            add(16L + String.valueOf(id).length() * 2L);
            Object child = id instanceof Number n ? object.get(n.intValue(), object) : object.get((String) id, object);
            scan(child, depth + 1);
        }
        if (object instanceof JSFunction function) {
            scan(function.getDeclarationScope(), depth + 1);
            scan(function.getHomeObject(), depth + 1);
            scan(function.getFunctionThis(root), depth + 1);
        } else if (object.getClassName().equals("Call")) scan(object.getParentScope(), depth + 1);
    }

    private void add(long amount) { bytes += amount; if (bytes > AppScope.MAX_RETAINED_CHARS) abort("前端跨调用驻留超过 1 MiB"); }
    private static void abort(String reason) { throw new ScriptAbort(ScriptAbort.Reason.RETAINED, reason); }
}
