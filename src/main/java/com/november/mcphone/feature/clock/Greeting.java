package com.november.mcphone.feature.clock;

/**
 * 该对屏幕前的人说哪一句。
 *
 * ================================================================
 * 为什么按情境挑，不随机
 * ================================================================
 *
 * 随机箴言两天就腻，而且假——玩家会看出那是从一个列表里抽的，抽到第三遍
 * 的时候它就成了噪音。按情境挑才像有人在看着你：你刚坐下它说欢迎回来，
 * 你坐了三小时它就知道你坐了三小时。
 *
 * ================================================================
 * 按【现实时间】问好，不按游戏时间
 * ================================================================
 *
 * 关怀的对象是屏幕前的人，不是游戏里的角色。游戏里可能正是正午，而现实里
 * 他已经凌晨两点了——这时候该说的是"夜深了"，不是"中午好"。
 *
 * 用游戏时间的话，那句唯一真正有分量的话永远说不出来。
 *
 * ================================================================
 * 分寸：只陈述，不说教
 * ================================================================
 *
 * 说"已经玩了三个多小时了"，不说"你该休息了"。前者像朋友随口一提，后者
 * 像家长。一个会被玩家在心里接一句"是啊"，另一个会被关掉。
 *
 * 唯一带建议的是深夜那句，而且只到"早点休息"为止——那是朋友会说的话。
 *
 * ================================================================
 * 为什么单独一个类，而且【不 import 任何 Minecraft 类型】
 * ================================================================
 *
 * 优先级判断是一串条件分支，而分支写错了不会崩：它只会在错误的时刻说错误
 * 的话——凌晨三点跟你说"下午好"。这种错误要在那个时刻恰好有人在看才发现
 * 得了，靠试玩根本试不出来。所以它得能用 javac 单独跑断言，把一天 24 小时
 * 乘各种状态全部走一遍。
 *
 * 与 {@link WorldClock}、{@link com.november.mcphone.feature.chat.FriendGraph}
 * 同一个考虑。
 */
public final class Greeting {

    private Greeting() {}

    // ============================================================
    //  阈值
    // ============================================================

    /** 进来多久之内算"刚坐下" */
    public static final long WELCOME_WINDOW_MS = 3 * 60 * 1000L;

    /** 本次玩到几小时开始提一句 */
    public static final long LONG_SESSION_HOURS = 3;

    /** 存档总时长每跨过这么多小时，说一句 */
    public static final long MILESTONE_HOURS = 100;

    /** 距离天黑还剩这么多现实秒时，提醒一句 */
    public static final int DARK_SOON_SECONDS = 120;

    /** 现实几点起算深夜 */
    public static final int LATE_NIGHT_FROM = 23;

    /** 现实几点起不再算深夜 */
    public static final int LATE_NIGHT_TO = 5;

    // ============================================================
    //  说什么
    // ============================================================

    /**
     * 每一项对应一个语言键 {@code mcphone.clock.greet.<suffix>}。
     *
     * 用枚举而不是直接返回字符串键：拼错一个键不会报错，只会把
     * "mcphone.clock.greet.moring" 原样画在手机上，而那一行恰恰是给人看的
     * 温馨话——出丑出得最彻底的地方。
     */
    public enum Kind {
        /** 刚坐下 */
        WELCOME_BACK("welcome_back"),
        /** 现实的深夜 */
        LATE_NIGHT("late_night"),
        /** 这一次坐得有点久了。带参数：几小时 */
        LONG_SESSION("long_session"),
        /** 存档陪了你整整多少小时。带参数：几小时 */
        MILESTONE("milestone"),
        /** 游戏里天要黑了 */
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

    /**
     * 挑出来的那一句。
     *
     * @param arg 语言键里那个 %s 的值；-1 表示这一句没有参数
     */
    public record Choice(Kind kind, long arg) {

        public boolean hasArg() {
            return arg >= 0;
        }
    }

    private static Choice of(Kind kind) {
        return new Choice(kind, -1);
    }

    // ============================================================
    //  挑
    // ============================================================

    /**
     * 按优先级从高到低挑一句。
     *
     * 顺序本身就是设计：
     *
     *   1. 刚坐下      —— 哪怕是凌晨一点坐下，先说"欢迎回来"。头三分钟就
     *                     劈头一句"夜深了"，像被数落
     *   2. 现实深夜    —— 三分钟过后再说。这是唯一带建议的一句
     *   3. 坐得久了    —— 只陈述事实
     *   4. 存档里程碑  —— 整百小时，难得一见，值得让位
     *   5. 天要黑了    —— 游戏里的事，排在关于人的几句之后
     *   6. 时段问好    —— 什么都不特殊时的默认
     *
     * @param realHour        现实几点，0–23
     * @param sessionMillis   本次已玩毫秒。调用方保证已经进了世界
     * @param worldPlayTicks  存档总时长（现实 tick）；-1 表示还不知道
     * @param night           游戏里是不是夜里
     * @param ticksUntilSunset 距离天黑还有多少 tick
     * @param frozen          游戏时间是不是停了
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

        // 里程碑只在整百那一小时之内说。说完这一小时它自己就沉下去了，
        // 不必记"说过没有"——那要存盘，而存盘就要处理换存档、多存档
        if (worldPlayTicks >= 0) {
            long totalHours = WorldClock.playHours(worldPlayTicks);
            if (totalHours >= MILESTONE_HOURS && totalHours % MILESTONE_HOURS == 0) {
                return new Choice(Kind.MILESTONE, totalHours);
            }
        }

        // 时间停了就没有"快天黑了"这回事——它永远不会黑
        if (!frozen && !night
                && WorldClock.toRealSeconds(ticksUntilSunset) <= DARK_SOON_SECONDS) {
            return of(Kind.DARK_SOON);
        }

        return of(bandOf(realHour));
    }

    /**
     * 深夜跨零点，所以是"或"不是"与"。
     *
     * 写成 hour >= 23 && hour < 5 的话它永远为假，而且不报错——凌晨三点
     * 什么都不会发生，只是那句最该说的话从来没出现过。
     */
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
