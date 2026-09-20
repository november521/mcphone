package com.november.mcphone.feature.music;

/** 唱片仓单曲循环的纯算术，不依赖 Minecraft，便于在裸 JVM 中回归。 */
public final class DiscLoop {

    private DiscLoop() {}

    /** 当前轮次是否已经到头。游戏刻倒流与非法长度都不触发重放。 */
    public static boolean isDue(long startedTick, long length, long now) {
        return length > 0L && now >= startedTick && now - startedTick >= length;
    }

    /**
     * 返回覆盖 {@code now} 的最近一轮起点。调用方应先用 {@link #isDue} 判断是否需要重放。
     * 延迟跨过多轮时仍落在原始节拍的整数倍边界上，避免每次卡顿都累积漂移。
     */
    public static long periodStart(long startedTick, long length, long now) {
        if (length <= 0L || now <= startedTick) return startedTick;
        return startedTick + ((now - startedTick) / length) * length;
    }
}
