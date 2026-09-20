package com.november.mcphone.core.script.server.economy;

import com.november.mcphone.core.script.engine.HostError;

import java.math.BigInteger;

/**
 * 脚本侧金额与宿主侧 {@code long} 之间的桥（施工方案 §22.3 ③，按勘误 E18）。
 *
 * <h2>脚本侧是 BigInt，不是字符串</h2>
 *
 * §22.3 ③ 的原文写"脚本侧金额是字符串"，那是 §16.2 实测出 Rhino 的 BigInt 可用<b>之前</b>的口径。
 * 实测（本轮复核）：
 *
 * <pre>
 * 脚本 BigInt('9007199254740993') → Java java.math.BigInteger
 * Java 塞 BigInteger 回去        → typeof 是 'bigint'，能直接 + 1n
 * BigInt 与 Number 混算          → TypeError（引擎自己拦）
 * </pre>
 *
 * 所以进出都是 {@code BigInteger}，<b>宿主侧一次字符串算术都不做</b> ——
 * 字符串算术是那条旧口径带来的，现在不需要了，而且它极容易写错。
 *
 * <h2>线格式仍然是十进制字符串</h2>
 *
 * JSON 没有 BigInt（§15.3）。所以：脚本侧 BigInt、宿主内部 long、线上十进制字符串，三段各归各。
 *
 * <h2>边界要查范围</h2>
 *
 * 脚本能造出超过 {@code long} 的 BigInt（实测 {@code Long.MAX_VALUE + 1n} 照样拿得到）。
 * 不查的话 {@code longValueExact} 会抛 {@code ArithmeticException}，
 * 那是个宿主异常、会被当成内部错误 —— 而它其实是个正常的"金额太大"。
 */
public final class Amounts {

    private Amounts() {
    }

    /** 脚本给的 BigInt → 宿主的 long。不是 BigInt、或者超出 long 范围，都当场中断本次调用。 */
    public static long toLong(Object scriptValue, String where) {
        if (!(scriptValue instanceof BigInteger b)) {
            throw HostError.invalid(
                    where + " 的金额要 BigInt（写成 123n 或 BigInt('123')），不是数字也不是字符串");
        }
        if (b.bitLength() > 63) {
            throw HostError.invalid(where + " 的金额超出 long 范围：" + b);
        }
        return b.longValueExact();
    }

    /** 宿主的 long → 脚本能直接用的 BigInt。 */
    public static BigInteger toScript(long value) {
        return BigInteger.valueOf(value);
    }

    /** 线格式：十进制字符串（§15.3，JSON 没有 BigInt）。 */
    public static String toWire(long value) {
        return Long.toString(value);
    }

    /** 从线格式读回来。 */
    public static long fromWire(String wire) {
        return Long.parseLong(wire);
    }
}
