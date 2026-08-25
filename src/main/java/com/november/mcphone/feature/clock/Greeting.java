package com.november.mcphone.feature.clock;

/**
 * 按现实时间和游戏状态挑一句问候语。
 * 故意不 import 任何 Minecraft 类型，好让分支逻辑能用 javac 单独跑断言。
 */
public final class Greeting {

    private Greeting() {}

    public static final long WELCOME_WINDOW_MS = 3 * 60 * 1000L;

    public static final long LONG_SESSION_HOURS = 3;

    /** 存档总时长每跨过这么多小时，说一句 */
    public static final long MILESTONE_HOURS = 100;

    /** 距离天黑还剩这么多现实秒时，提醒一句 */
    public static final int DARK_SOON_SECONDS = 120;

    /** 深夜的现实钟点区间，跨零点 */
    public static final int LATE_NIGHT_FROM = 23;

    public static final int LATE_NIGHT_TO = 5;

    /** 每一项对应一个语言键 {@code mcphone.clock.greet.<suffix>} */
    public enum Kind {
        WELCOME_BACK("welcome_back"),
        LATE_NIGHT("late_night"),
        LONG_SESSION("long_session"),
        MILESTONE("milestone"),
        DARK_SOON("dark_soon"),
        MORNING("morning"),
        NOON("noon"),
        AFTERNOON("afternoon"),
        EVENING("evening");

        private final String suffix;

        Kind(String suffix) {
            this.suffix = suffix;
        }

        public String key() {
            return "mcphone.clock.greet." + suffix;
        }
    }

    /** arg 是语言键里 %s 的值：只有 LONG_SESSION / MILESTONE 传小时数，其余传 -1 */
    public record Choice(Kind kind, long arg) {

        public boolean hasArg() {
            return arg >= 0;
        }
    }

    private static Choice of(Kind kind) {
        return new Choice(kind, -1);
    }

    /**
     * 按优先级从高到低挑一句。
     * worldPlayTicks 是现实 tick（20/秒）、-1 表示还不知道；ticksUntilSunset 是昼夜 tick（24000/天）。
     */
    public static Choice choose(int realHour, long sessionMillis, long worldPlayTicks,
                                boolean night, int ticksUntilSunset, boolean frozen) {

        if (sessionMillis >= 0 && sessionMillis <= WELCOME_WINDOW_MS) {
            return of(Kind.WELCOME_BACK);
        }

        if (isLateNight(realHour)) {
            return of(Kind.LATE_NIGHT);
        }

        long sessionHours = WorldClock.playHoursOfMillis(sessionMillis);
        if (sessionHours >= LONG_SESSION_HOURS) {
            return new Choice(Kind.LONG_SESSION, sessionHours);
        }

        // 只在整百那一小时之内说，省掉记"说过没有"的存盘
        if (worldPlayTicks >= 0) {
            long totalHours = WorldClock.playHours(worldPlayTicks);
            if (totalHours >= MILESTONE_HOURS && totalHours % MILESTONE_HOURS == 0) {
                return new Choice(Kind.MILESTONE, totalHours);
            }
        }

        // 时间停了就永远不会黑
        if (!frozen && !night
                && WorldClock.toRealSeconds(ticksUntilSunset) <= DARK_SOON_SECONDS) {
            return of(Kind.DARK_SOON);
        }

        return of(bandOf(realHour));
    }

    /** 深夜跨零点，所以是"或"不是"与"：写成 hour >= 23 && hour < 5 永远为假且不报错 */
    public static boolean isLateNight(int realHour) {
        return realHour >= LATE_NIGHT_FROM || realHour < LATE_NIGHT_TO;
    }

    /** 按现实钟点分时段。深夜由 {@link #isLateNight} 单独接管，这里不重复 */
    public static Kind bandOf(int realHour) {
        if (realHour < 11) return Kind.MORNING;      // 5–10
        if (realHour < 13) return Kind.NOON;         // 11–12
        if (realHour < 18) return Kind.AFTERNOON;    // 13–17
        return Kind.EVENING;                          // 18–22
    }
}
