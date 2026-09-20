package com.november.mcphone.core.script.engine;

import java.math.BigInteger;

/** Exact JS BigInt/number to Java long conversion. */
public final class ExactLong {

    public static final long JS_SAFE_INT_MAX = 9_007_199_254_740_991L;
    public static final long JS_SAFE_INT_MIN = -JS_SAFE_INT_MAX;

    private ExactLong() {
    }

    public static long of(Object value, String where) {
        if (value instanceof BigInteger integer) {
            if (integer.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0
                    || integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                throw HostError.invalid(where + ": BigInt is outside the signed 64-bit range");
            }
            return integer.longValue();
        }
        if (value instanceof Number number) {
            double candidate = number.doubleValue();
            if (!Double.isFinite(candidate) || candidate != Math.rint(candidate)
                    || candidate < JS_SAFE_INT_MIN || candidate > JS_SAFE_INT_MAX) {
                throw HostError.invalid(where + ": number must be a finite safe integer");
            }
            return (long) candidate;
        }
        throw HostError.invalid(where + ": expected BigInt or number");
    }
}
