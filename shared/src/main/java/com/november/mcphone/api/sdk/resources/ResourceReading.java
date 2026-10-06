package com.november.mcphone.api.sdk.resources;
import java.math.BigInteger;

/** 多个 int/long 储罐先升为 BigInteger 再累加，不能先溢出再转换。 */
public record ResourceReading(BigInteger stored,BigInteger capacity,boolean canExtract,boolean canReceive) {
    public ResourceReading {valid(stored);valid(capacity);if(stored.compareTo(capacity)>0)throw new IllegalArgumentException("储量超过容量");}
    static void valid(BigInteger value){if(value==null||value.signum()<0||value.toString().length()>128)throw new IllegalArgumentException("资源数量无效");}
}
