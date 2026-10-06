package com.november.mcphone.core.script.server;

import java.util.concurrent.atomic.AtomicLong;

/** 服务端到达序号，不采用玩家时间与墙钟（§20.5）。 */
public final class Seq {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private Seq() { }
    public static long next() {
        long next = SEQUENCE.incrementAndGet();
        if (next <= 0) throw new IllegalStateException("请求序号耗尽");
        return next;
    }
}
