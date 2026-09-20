package com.november.mcphone.feature.music;

/** 唱片单曲循环的边界回归。 */
public final class DiscLoopTest {

    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void eq(long expected, long actual, String message) {
        check(expected == actual, message + "：期望 " + expected + "，实际 " + actual);
    }

    public static void main(String[] args) {
        long length = 100L;
        check(!DiscLoop.isDue(0L, length, 99L), "差一刻时不得重放");
        check(DiscLoop.isDue(0L, length, 100L), "到达边界时必须重放");
        check(DiscLoop.isDue(0L, length, 455L), "服务端跳刻后必须补接循环");
        check(!DiscLoop.isDue(500L, length, 499L), "游戏刻倒流不得重放");
        check(!DiscLoop.isDue(0L, 0L, 100L), "非法长度不得重放");

        eq(0L, DiscLoop.periodStart(0L, length, 99L), "首轮内起点不变");
        eq(100L, DiscLoop.periodStart(0L, length, 100L), "边界进入新一轮");
        eq(400L, DiscLoop.periodStart(0L, length, 455L), "跳刻仍落在整轮边界");
        eq(207L, DiscLoop.periodStart(7L, length, 208L), "非零起点保持节拍");

        long previous = 0L;
        int moves = 0;
        for (long now = 1L; now <= 20L; now++) {
            long period = DiscLoop.periodStart(0L, 7L, now);
            if (period != previous) {
                eq(previous + 7L, period, "每次只前进一整轮");
                previous = period;
                moves++;
            }
        }
        eq(2L, moves, "20 刻内长度 7 应切换两轮");
        System.out.println("Disc loop assertions: " + checks);
    }
}
