package com.november.mcphone.feature.clock;

import com.november.mcphone.feature.clock.Greeting.Choice;
import com.november.mcphone.feature.clock.Greeting.Kind;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Greeting 的断言测试，用 javac 单独编，不需要 Minecraft。 */
public class GreetingTest {

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

    static final long SETTLED = 30 * 60 * 1000L;   // 坐了半小时，过了欢迎窗口
    static final long UNKNOWN_TOTAL = -1;
    static final int NOON_SUNSET = 12000;          // 离天黑还早

    static Kind at(int hour) {
        return Greeting.choose(hour, SETTLED, UNKNOWN_TOTAL, false, NOON_SUNSET, false).kind();
    }

    public static void main(String[] args) {
        everyHourSaysSomething();
        lateNightWrapsMidnight();
        welcomeBeatsEverything();
        longSession();
        milestone();
        darkSoon();
        frozenTimeNeverWarnsAboutDark();
        keysAreWellFormed();
        priorityOrderIsStable();
        exhaustiveSweep();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + checks + " 条断言");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 条：");
            for (String f : failures) System.out.println("  ✗ " + f);
            System.exit(1);
        }
    }

    static void everyHourSaysSomething() {
        for (int h = 0; h < 24; h++) {
            Choice c = Greeting.choose(h, SETTLED, UNKNOWN_TOTAL, false, NOON_SUNSET, false);
            check(c != null && c.kind() != null, h + " 点没挑出任何一句");
        }
        eq(at(7),  Kind.MORNING,   "早上七点");
        eq(at(10), Kind.MORNING,   "上午十点");
        eq(at(11), Kind.NOON,      "十一点算中午");
        eq(at(12), Kind.NOON,      "正午");
        eq(at(13), Kind.AFTERNOON, "下午一点");
        eq(at(17), Kind.AFTERNOON, "下午五点");
        eq(at(18), Kind.EVENING,   "傍晚六点");
        eq(at(22), Kind.EVENING,   "晚上十点");
    }

    /** 坑：写成 hour >= 23 && hour < 5 永远为假且不报错 */
    static void lateNightWrapsMidnight() {
        check(Greeting.isLateNight(23), "23 点算深夜");
        check(Greeting.isLateNight(0),  "零点算深夜");
        check(Greeting.isLateNight(3),  "凌晨三点算深夜");
        check(Greeting.isLateNight(4),  "凌晨四点算深夜");
        check(!Greeting.isLateNight(5), "五点不算了");
        check(!Greeting.isLateNight(22),"22 点还不算");
        check(!Greeting.isLateNight(12),"正午当然不算");

        for (int h : new int[]{23, 0, 1, 2, 3, 4}) {
            eq(at(h), Kind.LATE_NIGHT, h + " 点该说夜深了");
        }
        int lateHits = 0;
        for (int h = 0; h < 24; h++) if (Greeting.isLateNight(h)) lateHits++;
        eq(lateHits, 6, "一天里该有 6 个钟点算深夜（23,0,1,2,3,4）");
    }

    static void welcomeBeatsEverything() {
        eq(Greeting.choose(1, 0, UNKNOWN_TOTAL, false, NOON_SUNSET, false).kind(),
                Kind.WELCOME_BACK, "刚坐下那一瞬");
        eq(Greeting.choose(1, Greeting.WELCOME_WINDOW_MS, UNKNOWN_TOTAL, false, NOON_SUNSET, false).kind(),
                Kind.WELCOME_BACK, "正好卡在窗口边界上仍算刚坐下");
        eq(Greeting.choose(1, Greeting.WELCOME_WINDOW_MS + 1, UNKNOWN_TOTAL, false, NOON_SUNSET, false).kind(),
                Kind.LATE_NIGHT, "过了窗口才轮到深夜");

        eq(Greeting.choose(2, 1000, 100 * 72000L, false, 100, false).kind(),
                Kind.WELCOME_BACK, "三件事撞一起时欢迎仍然最优先");
    }

    static void longSession() {
        long threeHours = Greeting.LONG_SESSION_HOURS * 3_600_000L;
        Choice c = Greeting.choose(14, threeHours, UNKNOWN_TOTAL, false, NOON_SUNSET, false);
        eq(c.kind(), Kind.LONG_SESSION, "坐满三小时该提一句");
        eq(c.arg(), 3L, "带上小时数");
        check(c.hasArg(), "这一句有参数");

        eq(Greeting.choose(14, threeHours - 1, UNKNOWN_TOTAL, false, NOON_SUNSET, false).kind(),
                Kind.AFTERNOON, "差一毫秒还不算久");

        eq(Greeting.choose(14, 7 * 3_600_000L, UNKNOWN_TOTAL, false, NOON_SUNSET, false).arg(),
                7L, "坐了七小时就说七小时");

        eq(Greeting.choose(2, 5 * 3_600_000L, UNKNOWN_TOTAL, false, NOON_SUNSET, false).kind(),
                Kind.LATE_NIGHT, "深夜优先于'坐得久'");
    }

    static void milestone() {
        long hundred = 100 * 72000L;
        Choice c = Greeting.choose(14, SETTLED, hundred, false, NOON_SUNSET, false);
        eq(c.kind(), Kind.MILESTONE, "存档满 100 小时");
        eq(c.arg(), 100L, "带上总小时数");

        eq(Greeting.choose(14, SETTLED, 200 * 72000L, false, NOON_SUNSET, false).arg(),
                200L, "200 小时也算");
        eq(Greeting.choose(14, SETTLED, 99 * 72000L, false, NOON_SUNSET, false).kind(),
                Kind.AFTERNOON, "99 小时还不到");
        eq(Greeting.choose(14, SETTLED, 101 * 72000L, false, NOON_SUNSET, false).kind(),
                Kind.AFTERNOON, "101 小时已经过去了");

        eq(Greeting.choose(14, SETTLED, 0, false, NOON_SUNSET, false).kind(),
                Kind.AFTERNOON, "0 小时不是里程碑");
        eq(Greeting.choose(14, SETTLED, UNKNOWN_TOTAL, false, NOON_SUNSET, false).kind(),
                Kind.AFTERNOON, "统计还没回来时不说里程碑");
    }

    static void darkSoon() {
        int twoMinutes = Greeting.DARK_SOON_SECONDS * 20;
        eq(Greeting.choose(14, SETTLED, UNKNOWN_TOTAL, false, twoMinutes, false).kind(),
                Kind.DARK_SOON, "还剩两分钟天黑");
        eq(Greeting.choose(14, SETTLED, UNKNOWN_TOTAL, false, twoMinutes + 20, false).kind(),
                Kind.AFTERNOON, "还剩两分零一秒就先不提");

        eq(Greeting.choose(14, SETTLED, UNKNOWN_TOTAL, true, 100, false).kind(),
                Kind.AFTERNOON, "夜里不该说天要黑了");
    }

    static void frozenTimeNeverWarnsAboutDark() {
        eq(Greeting.choose(14, SETTLED, UNKNOWN_TOTAL, false, 10, true).kind(),
                Kind.AFTERNOON, "doDaylightCycle 关掉时不报天黑");
        for (int t = 0; t <= 24000; t += 250) {
            Choice c = Greeting.choose(14, SETTLED, UNKNOWN_TOTAL, false, t, true);
            check(c.kind() != Kind.DARK_SOON, "时间停止时 t=" + t + " 仍报了天黑");
        }
    }

    static void keysAreWellFormed() {
        EnumSet<Kind> seen = EnumSet.noneOf(Kind.class);
        for (Kind k : Kind.values()) {
            String key = k.key();
            check(key.startsWith("mcphone.clock.greet."), k + " 的键前缀不对: " + key);
            check(key.length() > "mcphone.clock.greet.".length(), k + " 的键没有后缀");
            check(key.equals(key.toLowerCase()), k + " 的键里有大写: " + key);
            check(seen.add(k), k + " 重复");
        }
        long distinct = java.util.Arrays.stream(Kind.values()).map(Kind::key).distinct().count();
        eq(distinct, (long) Kind.values().length, "有两个 Kind 用了同一个键");
    }

    static void priorityOrderIsStable() {
        for (int i = 0; i < 100; i++) {
            eq(Greeting.choose(2, 5 * 3_600_000L, 100 * 72000L, false, 100, false).kind(),
                    Kind.LATE_NIGHT, "第 " + i + " 次调用结果应当一致");
        }
    }

    static void exhaustiveSweep() {
        long[] sessions = {0, 60_000L, SETTLED, 3 * 3_600_000L, 12 * 3_600_000L};
        long[] totals = {-1, 0, 50 * 72000L, 100 * 72000L, 137 * 72000L};
        int[] sunsets = {1, 100, 2400, 12000, 24000};

        int combos = 0;
        for (int h = 0; h < 24; h++)
            for (long s : sessions)
                for (long t : totals)
                    for (boolean night : new boolean[]{false, true})
                        for (boolean frozen : new boolean[]{false, true})
                            for (int sun : sunsets) {
                                Choice c = Greeting.choose(h, s, t, night, sun, frozen);
                                combos++;
                                if (c == null || c.kind() == null) {
                                    failures.add("组合无解: h=" + h + " s=" + s + " t=" + t
                                            + " night=" + night + " frozen=" + frozen + " sun=" + sun);
                                }
                                checks++;
                            }
        System.out.println("  全量扫描：" + combos + " 种组合，每一种都挑得出话");
    }
}
