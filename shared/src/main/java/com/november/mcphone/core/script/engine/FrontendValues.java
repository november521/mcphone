package com.november.mcphone.core.script.engine;

import org.mozilla.javascript.*;
import java.util.*;

/** 前端桥仅复制有界数据值，不调用对象转换、属性访问器或 Java 包装器。 */
final class FrontendValues {
    private FrontendValues() { }

    static Object read(Object value) {
        return read(value, 0, new int[1], Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Object read(Object value, int depth, int[] count, Set<Object> seen) {
        if (++count[0] > 16384 || depth > 8) throw HostError.invalid("前端数据层数或节点数超限");
        if (value == null || value == Undefined.instance || value == Scriptable.NOT_FOUND) return null;
        if (value instanceof Boolean) return value;
        if (value instanceof CharSequence chars) {
            SizeGate.check(chars, "前端数据");
            return chars.toString();
        }
        if (value instanceof Number n && !(n instanceof java.math.BigInteger)) {
            double number = n.doubleValue();
            if (!Double.isFinite(number) || number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)
                throw HostError.invalid("前端数据中的大数或小数须使用字符串");
            return (int) number;
        }
        if (!(value instanceof NativeObject) && !(value instanceof NativeArray))
            throw HostError.invalid("前端桥只接受普通对象、数组和原语");
        ScriptableObject object = (ScriptableObject) value;
        if (!seen.add(object)) throw HostError.invalid("前端数据不能循环引用");
        try {
            if (object instanceof NativeArray array) {
                if (array.getLength() > 64) throw HostError.invalid("前端数据数组最多 64 项");
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < array.getLength(); i++) {
                    noAccessor(object, i);
                    out.add(read(array.has(i, array) ? array.get(i, array) : null, depth + 1, count, seen));
                }
                for (Object id : array.getIds())
                    if (!(id instanceof Number)&&!(id instanceof String key&&key.length()<3&&key.matches("0|[1-9][0-9]*")&&Integer.parseInt(key)<array.getLength()))
                        throw HostError.invalid("前端数据数组不接受命名属性");
                return Collections.unmodifiableList(out);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Object id : object.getAllIds()) {
                if (!(id instanceof String key) || key.length() > 64 || out.size() == 64
                        || key.chars().anyMatch(Character::isISOControl) || Set.of("__proto__", "prototype", "constructor").contains(key))
                    throw HostError.invalid("前端数据对象键无效");
                noAccessor(object, key);
                out.put(key, read(object.get(key, object), depth + 1, count, seen));
            }
            return Collections.unmodifiableMap(out);
        } finally { seen.remove(object); }
    }

    static void noAccessor(ScriptableObject object, Object id) {
        String key = id instanceof Number ? null : String.valueOf(id);
        int index = id instanceof Number n ? n.intValue() : 0;
        if (object.getGetterOrSetter(key, index, false) instanceof Callable
                || object.getGetterOrSetter(key, index, true) instanceof Callable)
            throw HostError.invalid("前端桥不读取属性访问器");
    }

    static Object write(Context cx, Scriptable scope, Object value, boolean frozen) {
        if (value instanceof Map<?, ?> map) {
            ScriptableObject out = HostFn.obj(cx, scope);
            for (var entry : map.entrySet()) {
                String key = (String) entry.getKey();
                Object child = write(cx, scope, entry.getValue(), frozen);
                if (frozen) out.defineProperty(key, child, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
                else out.put(key, out, child);
            }
            if (frozen) out.sealObject();
            return out;
        }
        if (value instanceof List<?> list) {
            Object[] values = new Object[list.size()];
            for (int i = 0; i < values.length; i++) values[i] = write(cx, scope, list.get(i), frozen);
            NativeArray out = (NativeArray) cx.newArray(scope, values);
            if (frozen) {
                for (int i = 0; i < values.length; i++) out.setAttributes(i, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
                out.setAttributes("length", ScriptableObject.READONLY | ScriptableObject.PERMANENT | ScriptableObject.DONTENUM);
                out.sealObject();
            }
            return out;
        }
        if (value == null || value instanceof String || value instanceof Integer || value instanceof Boolean) return value;
        throw new IllegalArgumentException("宿主前端值域无效");
    }
}
