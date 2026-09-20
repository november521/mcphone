package com.november.mcphone.core.script.engine;

import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 边界尺寸闸（施工方案 §16.4 的"必须做的三件事 ①"）。
 *
 * <h2>为什么不能指望指令预算</h2>
 *
 * 指令预算与墙钟<b>只在 Rhino 的分支点生效</b>，单个原生操作里观察器一次都不触发。实测：
 *
 * <pre>
 * new ArrayBuffer(2e8)      19 ms 分配 200 MB，观察器回调 0 次
 * new Int8Array(2e8)        94 ms                回调 0 次
 * 'x'.repeat(1e8)          158 ms                回调 0 次
 * JSON.stringify(16M 串)   419 ms                回调 0 次
 * s+=s 翻倍 30 次再 .length  4 ms 得到 1073741824  回调 0 次
 * </pre>
 *
 * 所以尺寸要在<b>进出宿主与内置方法的每个边界</b>上量，而不是等预算。
 *
 * <h2>量长度不会把 rope 物化</h2>
 *
 * Rhino 的字符串拼接是惰性的（{@code ConsString}），{@code s+=s} 不真复制；
 * 一旦被 {@code charAt} / {@code indexOf} 强制物化，9 毫秒就能打爆 256 MB 堆。
 * 而 {@code CharSequence.length()} 对 {@code ConsString} 是 O(1)，<b>量它是安全的</b>。
 */
public final class SizeGate {

    private SizeGate() {
    }

    /** 字符串上限，§16.4 ①。 */
    public static final int MAX_STRING = 64 * 1024;

    /** 数组上限，§16.4 ①。 */
    public static final int MAX_ARRAY = 4096;

    /** 一个值过不过得了闸。过不了就中断本次调用。 */
    public static void check(Object value, String where) {
        if (value instanceof CharSequence cs) {
            // length() 对 ConsString 是 O(1)，不会因为量尺寸把它物化
            if (cs.length() > MAX_STRING) {
                throw HostError.invalid(
                        where + ": 字符串 " + cs.length() + " 字符，上限 " + MAX_STRING);
            }
            return;
        }
        if (value instanceof NativeArray arr) {
            long n = arr.getLength();
            if (n > MAX_ARRAY) {
                throw HostError.invalid(
                        where + ": 数组 " + n + " 个元素，上限 " + MAX_ARRAY);
            }
            return;
        }
        // Deliberately do not inspect arbitrary Scriptable values here. Reading a script-owned
        // length property executes its getter. The old read-then-call sequence was a TOCTOU hole:
        // a getter could report 1 here and 10^10 inside Array.prototype.indexOf.
    }

    /** 一批值（参数表）。 */
    public static void checkAll(Object[] values, String where) {
        if (values == null) return;
        for (Object v : values) check(v, where);
    }

    /** Validate one wrapped Rhino native call before the native implementation gets control. */
    public static void checkNativeCall(String holder, String method, Object thisObj, Object[] args) {
        if ("JSON".equals(holder)) {
            checkAll(args, holder + "." + method + " 的参数");
            if ("stringify".equals(method) && args != null && args.length > 0) {
                long estimate = jsonCost(args[0], Collections.newSetFromMap(new IdentityHashMap<>()), 0);
                if (estimate > MAX_STRING) tooLarge(holder + "." + method, estimate, MAX_STRING);
            }
            return;
        }

        if ("Array.prototype".equals(holder)) {
            if (!(thisObj instanceof NativeArray array)) {
                throw HostError.invalid(holder + "." + method + ": 只接受原生数组接收者");
            }
            check(array, holder + "." + method);
            checkAll(args, holder + "." + method + " 的参数");
            checkArrayAmplification(method, array, args);
            return;
        }

        if ("String.prototype".equals(holder)) {
            long receiverLength = stringReceiverLength(thisObj, holder + "." + method);
            checkAll(args, holder + "." + method + " 的参数");
            checkStringAmplification(method, receiverLength, args);
        }
    }

    private static long stringReceiverLength(Object value, String where) {
        if (value instanceof CharSequence cs) {
            check(cs, where);
            return cs.length();
        }
        // Rhino's NativeString is package-private. Its unforgeable Java-side class name is
        // exposed as getClassName(); its built-in length is fixed and non-configurable.
        if (value instanceof Scriptable s && "String".equals(s.getClassName())) {
            Object length = ScriptableObject.getProperty(s, "length");
            if (length instanceof Number n) {
                long result = n.longValue();
                if (result > MAX_STRING) tooLarge(where, result, MAX_STRING);
                return result;
            }
        }
        throw HostError.invalid(where + ": 只接受原生字符串接收者");
    }

    private static void checkStringAmplification(String method, long receiverLength, Object[] args) {
        if ("repeat".equals(method)) {
            long count = nonNegativeInteger(args, 0, "String.repeat");
            checkProduct("String.repeat", receiverLength, count);
        } else if ("padStart".equals(method) || "padEnd".equals(method)) {
            long target = nonNegativeInteger(args, 0, "String." + method);
            if (target > MAX_STRING) tooLarge("String." + method, target, MAX_STRING);
        } else if ("concat".equals(method)) {
            long total = receiverLength;
            if (args != null) {
                for (Object arg : args) total = addBounded(total, primitiveStringLength(arg), "String.concat");
            }
        } else if ("replace".equals(method) || "replaceAll".equals(method)) {
            if (args != null && args.length > 1 && args[1] instanceof org.mozilla.javascript.Callable) {
                throw HostError.invalid("String." + method + ": 函数替换器不可在受限沙箱中使用");
            }
            long replacement = args != null && args.length > 1 ? primitiveStringLength(args[1]) : 0;
            long worst = addBounded(receiverLength,
                    multiplyBounded(receiverLength + 1, replacement, "String." + method),
                    "String." + method);
            if (worst > MAX_STRING) tooLarge("String." + method, worst, MAX_STRING);
        } else if ("split".equals(method)) {
            long limit = args != null && args.length > 1
                    && args[1] != org.mozilla.javascript.Undefined.instance
                    ? nonNegativeInteger(args, 1, "String.split") : Long.MAX_VALUE;
            if (Math.min(receiverLength + 1, limit) > MAX_ARRAY) {
                tooLarge("String.split", Math.min(receiverLength + 1, limit), MAX_ARRAY);
            }
        }
    }

    private static void checkArrayAmplification(String method, NativeArray array, Object[] args) {
        long length = array.getLength();
        if ("push".equals(method) || "unshift".equals(method)) {
            long added = args == null ? 0 : args.length;
            if (length + added > MAX_ARRAY) tooLarge("Array." + method, length + added, MAX_ARRAY);
        } else if ("concat".equals(method)) {
            long total = length;
            if (args != null) {
                for (Object arg : args) {
                    total += arg instanceof NativeArray nested ? nested.getLength() : 1;
                    if (total > MAX_ARRAY) tooLarge("Array.concat", total, MAX_ARRAY);
                }
            }
        } else if ("join".equals(method) || "toString".equals(method)
                || "toLocaleString".equals(method)) {
            long total = arrayStringCost(array, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
            if (total > MAX_STRING) tooLarge("Array." + method, total, MAX_STRING);
        }
    }

    private static long arrayStringCost(NativeArray array, Set<Object> seen, int depth) {
        if (depth > 16 || !seen.add(array)) return 0;
        long total = Math.max(0, array.getLength() - 1); // separators
        for (int i = 0; i < array.getLength(); i++) {
            Object getter = array.getGetterOrSetter(null, i, false);
            if (getter instanceof org.mozilla.javascript.Callable) {
                throw HostError.invalid("Array.join: 数组访问器不可用于放大型原生操作");
            }
            Object value = array.get(i, array);
            if (value instanceof NativeArray nested) {
                total = addBounded(total, arrayStringCost(nested, seen, depth + 1), "Array.join");
            } else {
                total = addBounded(total, primitiveStringLength(value), "Array.join");
            }
        }
        seen.remove(array);
        return total;
    }

    /** Conservative JSON output estimate without invoking script getters or toJSON hooks. */
    private static long jsonCost(Object value, Set<Object> seen, int depth) {
        if (value == null || value == org.mozilla.javascript.Undefined.instance) return 4;
        if (value instanceof CharSequence cs) return 2L + 6L * cs.length();
        if (value instanceof Number || value instanceof Boolean || value instanceof java.math.BigInteger) return 32;
        if (!(value instanceof ScriptableObject object)) return 32;
        if (depth > 32 || !seen.add(object)) {
            throw HostError.invalid("JSON.stringify: 对象过深或存在循环");
        }
        if (object.has("toJSON", object)) {
            throw HostError.invalid("JSON.stringify: 不允许脚本 toJSON 回调");
        }
        long total = 2;
        int count = 0;
        for (Object id : object.getIds()) {
            if (++count > MAX_ARRAY) tooLarge("JSON.stringify 属性数", count, MAX_ARRAY);
            Object getter = id instanceof Number n
                    ? object.getGetterOrSetter(null, n.intValue(), false)
                    : object.getGetterOrSetter(String.valueOf(id), 0, false);
            if (getter instanceof org.mozilla.javascript.Callable) {
                throw HostError.invalid("JSON.stringify: 不允许属性访问器");
            }
            Object child = id instanceof Number n
                    ? object.get(n.intValue(), object) : object.get(String.valueOf(id), object);
            total = addBounded(total, 8L + 6L * String.valueOf(id).length(), "JSON.stringify");
            total = addBounded(total, jsonCost(child, seen, depth + 1), "JSON.stringify");
        }
        seen.remove(object);
        return total;
    }

    private static long primitiveStringLength(Object value) {
        if (value == null || value == org.mozilla.javascript.Undefined.instance
                || value == Scriptable.NOT_FOUND) return 0;
        if (value instanceof CharSequence cs) return cs.length();
        if (value instanceof Number || value instanceof Boolean || value instanceof java.math.BigInteger) return 32;
        throw HostError.invalid("放大型原生操作的参数只能是原语");
    }

    private static long nonNegativeInteger(Object[] args, int index, String where) {
        if (args == null || index >= args.length || args[index] == org.mozilla.javascript.Undefined.instance) return 0;
        if (!(args[index] instanceof Number number)) throw HostError.invalid(where + ": 长度必须是数字");
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < 0 || value != Math.rint(value)) {
            throw HostError.invalid(where + ": 长度必须是非负整数");
        }
        return value >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) value;
    }

    private static void checkProduct(String where, long left, long right) {
        long value = multiplyBounded(left, right, where);
        if (value > MAX_STRING) tooLarge(where, value, MAX_STRING);
    }

    private static long multiplyBounded(long left, long right, String where) {
        if (left == 0 || right == 0) return 0;
        if (left > MAX_STRING || right > MAX_STRING || left > MAX_STRING / right) {
            tooLarge(where, (long) MAX_STRING + 1, MAX_STRING);
        }
        return left * right;
    }

    private static long addBounded(long left, long right, String where) {
        if (left > MAX_STRING || right > MAX_STRING || left > MAX_STRING - right) {
            tooLarge(where, (long) MAX_STRING + 1, MAX_STRING);
        }
        return left + right;
    }

    private static void tooLarge(String where, long actual, long max) {
        throw HostError.invalid(where + ": 预估输出 " + actual + "，上限 " + max);
    }
}
