package com.november.mcphone.core.script.engine;

import java.math.BigInteger;

/**
 * 脚本数字 → Java {@code long} 的精确转换（S15h/S15i 任务 5，勘误 E33）。
 *
 * <h2>为什么不能用 {@code Number.longValue()}</h2>
 *
 * 那是<b>静默截断</b>：{@code 1.9 → 1}、{@code NaN → 0}、{@code Infinity → Long.MAX_VALUE}、
 * {@code 2^63 → Long.MAX_VALUE}。而调用方拿它去写存储 —— 也就是玩家的存档里被写进一个
 * <b>他从来没给过的数</b>，而且没有任何地方报错。这类错误只在几个月后"我的积分怎么少了"
 * 的时候才被发现，那时已经查不出是哪一次写坏的。
 *
 * <h2>收什么、不收什么</h2>
 *
 * <ul>
 *   <li>{@link BigInteger}（脚本的 {@code BigInt}）—— <b>完整</b> {@code long} 范围，
 *       含 {@link Long#MIN_VALUE} 与 {@link Long#MAX_VALUE}。这是规范路径。</li>
 *   <li>{@link Number}（脚本的 {@code number}）—— 为兼容旧脚本保留，但<b>只收有限、无小数、
 *       且落在 JS 安全整数范围内</b>（±2^53-1）的值。超出这个范围的双精度数已经表达不了整数了，
 *       收进来必然是在猜。</li>
 *   <li>别的（字符串、布尔、对象、undefined）—— 拒。</li>
 * </ul>
 *
 * <p>拒的方式是 {@link HostError#invalid}：<b>脚本接得住、不记过失、且写前就抛</b> ——
 * 所以失败前不会发生任何存储写入。这条是验收标准里"无截断、无写入"那一句的落点。
 */
public final class ExactLong {

    private ExactLong() {
    }

    /** JS 能精确表达的最大整数，{@code 2^53 - 1}。 */
    public static final long JS_SAFE_INT_MAX = 9007199254740991L;

    /** JS 能精确表达的最小整数，{@code -(2^53 - 1)}。 */
    public static final long JS_SAFE_INT_MIN = -9007199254740991L;

    /**
     * 转一个精确的 {@code long}。转不出来抛 {@link HostError}（可接住、不记过失、调用方尚未写入）。
     *
     * @param value 脚本递过来的值
     * @param where 出错时进说明的位置（如 {@code store.setLong}）
     */
    public static long of(Object value, String where) {
        if (value instanceof BigInteger b) {
            if (b.bitLength() > 63) {
                // bitLength 是"不含符号位的最少位数"：64 位及以上的绝对值装不进 long。
                // Long.MIN_VALUE 的 bitLength 恰好是 63，所以它能过 —— 边界是这么定的
                throw HostError.invalid(where + "：BigInt " + b + " 超出 long 范围");
            }
            return b.longValue();
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw HostError.invalid(where + "：数字要是有限值，收到 " + d);
            }
            if (d != Math.rint(d)) {
                throw HostError.invalid(where + "：只收整数，收到 " + n);
            }
            if (d > JS_SAFE_INT_MAX || d < JS_SAFE_INT_MIN) {
                // 双精度在这之外已经表达不了相邻整数，收进来就是在猜他要的是哪一个。
                // 要写到这个范围请改用 BigInt（那是脚本侧的正路，见 §23.3）
                throw HostError.invalid(where + "：超出 JS 安全整数范围 " + n + "，这么大的数请用 BigInt");
            }
            return (long) d;
        }
        throw HostError.invalid(where + "：要整数（BigInt 或 number），收到 "
                + (value == null ? "null" : value.getClass().getSimpleName()));
    }
}
