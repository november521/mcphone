package com.november.mcphone.feature.clock;

import java.util.ArrayList;
import java.util.List;

/**
 * WorldClock 的断言测试 —— 用 javac 单独编，不需要 Minecraft。
 *
 * 时间换算错了不会崩也不会报错，只会让所有人的时钟差 6 小时。
 * 所以每一个"人尽皆知"的时刻都在这里钉死一遍。
 */
public class WorldClockTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(what + "  期望 " + expected + "，实际 " + actual);
        }
    }

    static void check(boolean cond, String what) {
        checks++;
        if (!cond) failures.add(what);
    }

    static String hhmm(long dayTime) {
        return String.format("%02d:%02d", WorldClock.hour(dayTime), WorldClock.minute(dayTime));
    }

    public static void main(String[] args) {
        knownMoments();
        theSixHourTrap();
        wrapAround();
        negativeAndHuge();
        dayNumberMatchesVanilla();
        nightWindow();
        countdown();
        twelveHourClock();
        monotonicAcrossAWholeDay();
        playDuration();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + checks + " 条断言");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 条：");
            for (String f : failures) System.out.println("  ✗ " + f);
            System.exit(1);
        }
    }

    // ============================================================

    /** 人尽皆知的几个时刻，一个一个钉死 */
    static void knownMoments() {
        eq(hhmm(0),     "06:00", "dayTime 0 是清晨六点，不是午夜");
        eq(hhmm(6000),  "12:00", "6000 是正午");
        eq(hhmm(12000), "18:00", "12000 是日落开始");
        eq(hhmm(13000), "19:00", "13000 是完全天黑");
        eq(hhmm(18000), "00:00", "18000 是午夜");
        eq(hhmm(23000), "05:00", "23000 是日出开始");
        eq(hhmm(24000), "06:00", "24000 绕回清晨六点");
    }

    /**
     * 最容易犯的那个错：忘掉 6 小时偏移。
     *
     * 写成 dayTime * 24 / 24000 的话，下面每一条都会差 6 小时，
     * 而且代码看着完全合理。
     */
    static void theSixHourTrap() {
        eq(WorldClock.hour(0), 6, "忘掉偏移的话这里会是 0");
        eq(WorldClock.hour(18000), 0, "忘掉偏移的话这里会是 18");
        check(WorldClock.hour(0) != 0, "dayTime 0 绝不能是 0 点");
    }

    static void wrapAround() {
        eq(hhmm(24000), hhmm(0), "整整一天之后回到同一时刻");
        eq(hhmm(24000 * 7 + 6000), "12:00", "第七天的正午还是正午");
        eq(WorldClock.timeOfDay(24000 * 100 + 1234), 1234, "取模跨一百天仍然对");
        eq(WorldClock.minuteOfDay(18000), 0, "午夜是第 0 分钟");
        eq(WorldClock.minuteOfDay(17999) + 1, 1440, "午夜前一 tick 是当天最后一分钟");
    }

    /** 存档被手改过、或别的模组直接写 levelData 时可能出现的怪值 */
    static void negativeAndHuge() {
        check(WorldClock.timeOfDay(-1) >= 0, "负 dayTime 也要落在 0..23999");
        check(WorldClock.timeOfDay(-1) < 24000, "负 dayTime 取模后不能越界");
        eq(WorldClock.timeOfDay(-24000), 0, "负整天取模为 0");
        check(WorldClock.hour(-500) >= 0 && WorldClock.hour(-500) <= 23, "负值算出的小时仍合法");
        eq(WorldClock.dayNumber(-5000), 0L, "负 dayTime 的天数夹到 0，不能是负数天");

        long huge = Long.MAX_VALUE - 3;
        check(WorldClock.timeOfDay(huge) >= 0 && WorldClock.timeOfDay(huge) < 24000,
                "极大 dayTime 不溢出成负数");
        check(WorldClock.minuteOfDay(huge) >= 0 && WorldClock.minuteOfDay(huge) < 1440,
                "极大 dayTime 的分钟仍合法");
    }

    /** 必须和原版 /time query day 是同一个数：那条命令算的是 dayTime / 24000 */
    static void dayNumberMatchesVanilla() {
        eq(WorldClock.dayNumber(0), 0L, "世界第一天是第 0 天");
        eq(WorldClock.dayNumber(23999), 0L, "第一天的最后一刻还是第 0 天");
        eq(WorldClock.dayNumber(24000), 1L, "跨过 24000 才进第 1 天");
        eq(WorldClock.dayNumber(24000L * 137 + 5000), 137L, "第 137 天");
        for (long d = 0; d < 50; d++) {
            eq(WorldClock.dayNumber(d * 24000 + 12345), d, "第 " + d + " 天与原版一致");
        }
    }

    static void nightWindow() {
        check(!WorldClock.isNight(0),     "清晨不是夜里");
        check(!WorldClock.isNight(6000),  "正午不是夜里");
        check(!WorldClock.isNight(12000), "日落刚开始时天还亮着，不算夜里");
        check(!WorldClock.isNight(12999), "完全天黑前一 tick 仍不算");
        check(WorldClock.isNight(13000),  "13000 起算夜里");
        check(WorldClock.isNight(18000),  "午夜当然是夜里");
        check(WorldClock.isNight(22999),  "日出前一 tick 还是夜里");
        check(!WorldClock.isNight(23000), "日出开始就不算夜里了");
        check(!WorldClock.isNight(24000 * 3 + 6000), "跨天之后判断仍然对");
    }

    static void countdown() {
        eq(WorldClock.ticksUntilSunset(0), 12000, "清晨到日落是 12000 tick");
        eq(WorldClock.toRealSeconds(12000), 600, "12000 tick 是现实十分钟");
        eq(WorldClock.ticksUntilSunset(11999), 1, "还差一 tick 天黑");

        // 正好站在那个点上：要说"还有一整天"，不能说 0
        eq(WorldClock.ticksUntilSunset(12000), 24000, "恰在日落点时报的是下一次，不是 0");
        check(WorldClock.ticksUntilSunset(12000) != 0, "绝不能返回 0，否则界面会卡在'还有 0 秒'");

        eq(WorldClock.ticksUntilSunset(13000), 23000, "天黑之后要等到明天的日落");
        eq(WorldClock.ticksUntilSunrise(18000), 6000, "午夜到天亮 6000 tick");
        eq(WorldClock.toRealSeconds(6000), 300, "6000 tick 是现实五分钟");
        eq(WorldClock.ticksUntilSunrise(0), 24000, "天刚亮时到下次天亮是一整天");

        // 返回值必须永远落在 1..24000
        for (int t = 0; t < 24000; t += 7) {
            int u = WorldClock.ticksUntilSunset(t);
            check(u >= 1 && u <= 24000, "t=" + t + " 时倒计时越界: " + u);
        }
    }

    static void twelveHourClock() {
        eq(WorldClock.hour12(18000), 12, "午夜是 12 点，不是 0 点");
        eq(WorldClock.hour12(6000),  12, "正午也是 12 点");
        eq(WorldClock.hour12(0),      6, "清晨六点");
        eq(WorldClock.hour12(12000),  6, "傍晚六点");
        check(WorldClock.isMorning(0),      "清晨是上午");
        check(WorldClock.isMorning(18000),  "午夜算上午（AM）");
        check(!WorldClock.isMorning(6000),  "正午算下午（PM）");
        check(!WorldClock.isMorning(12000), "傍晚是下午");
        for (int t = 0; t < 24000; t += 13) {
            int h = WorldClock.hour12(t);
            check(h >= 1 && h <= 12, "t=" + t + " 的 12 小时制越界: " + h);
        }
    }

    /**
     * 游玩时长。
     *
     * 这里的 tick 是【现实时间】的 tick（20/秒），跟上面昼夜循环的 tick
     * （24000/天）不是一回事。两者都是 long，混用编译得过——而混用的结果是
     * 把 106 小时显示成 3 小时半，看着还挺像个正常数字。
     */
    static void playDuration() {
        eq(WorldClock.playHours(0), 0L, "零 tick 是零小时");
        eq(WorldClock.playMinutes(0), 0, "零 tick 是零分");

        eq(WorldClock.playHours(72000), 1L, "72000 现实 tick 正好一小时");
        eq(WorldClock.playMinutes(72000), 0, "整一小时的余数是 0 分");
        eq(WorldClock.playMinutes(1200), 1, "1200 tick 是一分钟");
        eq(WorldClock.playMinutes(1199), 0, "差一 tick 不满一分钟");

        // 一个真实的老存档：106 小时 8 分
        long ticks = 106 * 72000L + 8 * 1200L;
        eq(WorldClock.playHours(ticks), 106L, "106 小时");
        eq(WorldClock.playMinutes(ticks), 8, "零 8 分");

        // 混用两种 tick 的话这一条会红：按 24000/天 算，106 小时会变成别的数
        check(WorldClock.playHours(ticks) != ticks / 24000,
                "现实 tick 与昼夜 tick 绝不能用同一个除数");

        eq(WorldClock.playMinutes(72000 * 3 + 1200 * 59), 59, "分钟余数上限是 59");
        eq(WorldClock.playMinutes(72000 * 3 + 1200 * 60), 0, "满 60 分进位成一小时");
        eq(WorldClock.playHours(72000 * 3 + 1200 * 60), 4L, "满 60 分之后是第 4 小时");

        // 毫秒那一路
        eq(WorldClock.playHoursOfMillis(3_600_000L), 1L, "一小时的毫秒");
        eq(WorldClock.playMinutesOfMillis(3_600_000L), 0, "整一小时余 0 分");
        eq(WorldClock.playMinutesOfMillis(83 * 60_000L), 23, "83 分钟 = 1 小时 23 分");
        eq(WorldClock.playHoursOfMillis(83 * 60_000L), 1L, "83 分钟的小时数");

        // 统计值不该是负的，但存档被动过时不必为此崩
        eq(WorldClock.playHours(-1), 0L, "负 tick 夹到 0 小时");
        eq(WorldClock.playMinutes(-999999), 0, "负 tick 夹到 0 分");
        eq(WorldClock.playHoursOfMillis(-1), 0L, "负毫秒夹到 0");

        // 两条路对同一段时长必须给出同一个答案
        for (int h = 0; h < 200; h += 7) {
            for (int m = 0; m < 60; m += 11) {
                long t = h * 72000L + m * 1200L;
                long ms = h * 3_600_000L + m * 60_000L;
                eq(WorldClock.playHours(t), WorldClock.playHoursOfMillis(ms),
                        h + "小时" + m + "分：tick 与毫秒两条路的小时数不一致");
                eq(WorldClock.playMinutes(t), WorldClock.playMinutesOfMillis(ms),
                        h + "小时" + m + "分：tick 与毫秒两条路的分钟数不一致");
            }
        }
    }

    /** 走完一整天：分钟必须单调递增、恰好绕一圈、且每一分钟都出现过 */
    static void monotonicAcrossAWholeDay() {
        boolean[] seen = new boolean[1440];
        int prev = WorldClock.minuteOfDay(0);
        int wraps = 0;

        for (int t = 0; t < 24000; t++) {
            int m = WorldClock.minuteOfDay(t);
            check(m >= 0 && m < 1440, "t=" + t + " 分钟越界");
            seen[m] = true;
            if (m < prev) wraps++;      // 只该在午夜绕一次
            prev = m;
        }
        eq(wraps, 1, "一整天里分钟只该绕回去一次（午夜）");

        int missing = 0;
        for (boolean b : seen) if (!b) missing++;
        eq(missing, 0, "一天 1440 分钟每一分钟都该被走到");
    }
}
