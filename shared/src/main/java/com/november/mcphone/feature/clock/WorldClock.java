package com.november.mcphone.feature.clock;

/**
 * 游戏时间的换算 —— tick 与"几点几分"、第几天、还有多久天黑。
 *
 * 为什么单独一个类，而且【不 import 任何 Minecraft 类型】
 *
 * 这里全是纯算术。而时间换算错了【不会崩，也不会报错】——它只是让所有人的
 * 时钟差 6 小时，或者把黄昏算成正午。这种错误编译得过、跑得起来、单元之外
 * 看不出来，只有玩家抬头看天说"这不对"。
 *
 * 单独摆出来、不碰 Minecraft，就能用 javac 直接编出来跑断言。与
 * {@link com.november.mcphone.core.client.HomeLayout} 和
 * {@link com.november.mcphone.feature.chat.FriendGraph} 是同一个考虑。
 *
 * Minecraft 的时间到底是怎么回事
 *
 * 【0 不是午夜，是早上 6 点】
 *
 * 这是整个换算里最容易错的一处，而且错了正好差 6 小时——一个大到离谱、
 * 却又不至于让人立刻怀疑代码的偏移量。世界刚生成时 dayTime = 0，玩家睁眼
 * 是清晨，不是半夜。所以换算时要先加 360 分钟。
 *
 *     dayTime      时刻      发生什么
 *          0      06:00      日出结束，天亮
 *       6000      12:00      正午，太阳最高
 *      12000      18:00      日落开始，天开始暗
 *      13000      19:00      完全天黑，怪物开始刷
 *      18000      00:00      午夜
 *      23000      05:00      日出开始
 *      24000      06:00      回到 0
 *
 * 【dayTime 与 gameTime 是两回事】
 *
 *   dayTime   表盘。/time set 能改它；doDaylightCycle 关掉时它【不再前进】
 *   gameTime  世界总 tick 数。谁都改不动，永远前进
 *
 * "第几天"用 dayTime / 24000，与原版 /time query day 一致。用 gameTime 算的话，
 * 玩家 /time set day 之后两个数会对不上——他看到的天数会跟命令的效果矛盾。
 *
 * 【下界和末地】
 *
 * 原版时钟在下界会乱转，是刻意的设计。但 dayTime 在下界照常同步，所以这里
 * 算出来的是【地表的真实时间】。挖矿的人最想知道的就是"我上去会不会天黑"，
 * 原版偏偏不给——这是本模组比原版强的地方，不是要绕开的坑。
 */
public final class WorldClock {

    private WorldClock() {}

    /** 一个 MC 日多少 tick */
    public static final int TICKS_PER_DAY = 24000;

    /** 一个现实日多少分钟，用来把 tick 换算成时钟上的分 */
    private static final int MINUTES_PER_DAY = 1440;

    /** dayTime = 0 时是早上 6 点，也就是第 360 分钟 */
    private static final int DAWN_OFFSET_MINUTES = 6 * 60;

    /** 游戏每秒 20 tick，用来把"还有多少 tick"换算成现实秒 */
    public static final int TICKS_PER_REAL_SECOND = 20;

    // -- 几个关键时刻，按 dayTime 算 --

    /** 日落开始，天开始变暗 */
    public static final int SUNSET_START = 12000;

    /** 完全天黑，怪物开始刷 */
    public static final int NIGHT_START = 13000;

    /** 日出开始，天开始变亮 */
    public static final int SUNRISE_START = 23000;

    //  时刻

    /**
     * 这一天过到第几 tick 了，0–23999。
     *
     * dayTime 本身是累加的（不会在 24000 处归零），所以要先取模。
     * 取模前先夹成非负：/time set 理论上给不出负数，但存档被手改过、
     * 或别的模组直接写 levelData 时可能出现，而 Java 的 % 对负数返回负数，
     * 那会让后面所有换算一起变成负的。
     */
    public static int timeOfDay(long dayTime) {
        int t = (int) (dayTime % TICKS_PER_DAY);
        return t < 0 ? t + TICKS_PER_DAY : t;
    }

    /**
     * 换算成一天中的第几分钟，0–1439。0 表示午夜 00:00。
     *
     * 先按比例把 tick 折成分钟，再加上 6 小时的开局偏移。
     * 1440/24000 可以约成 3/50，这里【不约】：写成 MINUTES_PER_DAY 与
     * TICKS_PER_DAY 相除，式子自己说得清"一天有多少分钟、多少 tick"，
     * 而 3/50 只是两个凭空出现的数字。
     */
    public static int minuteOfDay(long dayTime) {
        int t = timeOfDay(dayTime);
        int minutes = (int) ((long) t * MINUTES_PER_DAY / TICKS_PER_DAY);
        return (minutes + DAWN_OFFSET_MINUTES) % MINUTES_PER_DAY;
    }

    /** 时，0–23（24 小时制） */
    public static int hour(long dayTime) {
        return minuteOfDay(dayTime) / 60;
    }

    /** 分，0–59 */
    public static int minute(long dayTime) {
        return minuteOfDay(dayTime) % 60;
    }

    /**
     * 换算成 12 小时制的钟点，1–12。
     *
     * 0 点与 12 点都要显示成 12，不是 0——没有"上午 0 点"这种说法。
     */
    public static int hour12(long dayTime) {
        int h = hour(dayTime) % 12;
        return h == 0 ? 12 : h;
    }

    /** 是上午吗（12 小时制显示 AM/PM 用）。午夜到中午之前为 true */
    public static boolean isMorning(long dayTime) {
        return hour(dayTime) < 12;
    }

    /**
     * 第几天，与原版 /time query day 一致。
     *
     * 从 0 开始数：世界的第一天是第 0 天。要显示成"第 1 天"由界面自己加，
     * 这里不替它决定——加在这里的话，任何想跟原版命令对数的人都会差一。
     */
    public static long dayNumber(long dayTime) {
        return Math.max(0, dayTime) / TICKS_PER_DAY;
    }

    //  昼夜

    /**
     * 现在是不是完全的夜里（怪物会刷的那段）。
     *
     * 用 NIGHT_START 而不是 SUNSET_START：日落那 1000 tick 天还亮着，
     * 那会儿说"夜里"，玩家抬头一看不是。
     */
    public static boolean isNight(long dayTime) {
        int t = timeOfDay(dayTime);
        return t >= NIGHT_START && t < SUNRISE_START;
    }

    /**
     * 距离下一次某个时刻还有多少 tick。
     *
     * 已经过了今天那个点就算到明天的，所以返回值总在 1–24000 之间。
     * 恰好就在那个点上时返回一整天，而不是 0——0 会让界面显示
     * "还有 0 秒天黑"并一直卡着，说"还有 20 分钟"才是对的。
     *
     * @param target 目标时刻，按 dayTime 算（如 {@link #SUNSET_START}）
     */
    public static int ticksUntil(long dayTime, int target) {
        int t = timeOfDay(dayTime);
        int goal = Math.floorMod(target, TICKS_PER_DAY);
        int diff = goal - t;
        return diff <= 0 ? diff + TICKS_PER_DAY : diff;
    }

    /** 距离天开始黑（日落）还有多少 tick */
    public static int ticksUntilSunset(long dayTime) {
        return ticksUntil(dayTime, SUNSET_START);
    }

    /** 距离天亮还有多少 tick */
    public static int ticksUntilSunrise(long dayTime) {
        return ticksUntil(dayTime, 0);
    }

    /**
     * tick 换算成现实秒。
     *
     * 只在服务端跑满 20 TPS 时准确。卡服时游戏时间走得比这慢，显示出来的
     * "还有 3 分钟天黑"会偏乐观——但按当前 TPS 去修正会让这个数字不停跳动，
     * 比偏一点更难用。
     */
    public static int toRealSeconds(int ticks) {
        return ticks / TICKS_PER_REAL_SECOND;
    }

    //  游玩时长
    //
    // 【这里的 tick 与上面那些不是一回事】
    //
    // 上面全是昼夜循环的 tick：一"天" 24000 个，一个 MC 日等于现实 20 分钟。
    // 这里是【现实时间】的 tick：一秒 20 个，一小时 72000 个。原版
    // Stats.PLAY_TIME 记的是后者。
    //
    // 两者都叫 tick，都是 long，混用编译得过——而混用的结果是把 106 小时
    // 显示成 3 小时半，看着像个正常数字。所以这一组方法一律带 play 前缀，
    // 参数名一律写 realTicks，不叫 ticks。

    /** 一小时有多少个现实 tick */
    private static final long TICKS_PER_REAL_HOUR = TICKS_PER_REAL_SECOND * 3600L;

    /** 一分钟有多少个现实 tick */
    private static final long TICKS_PER_REAL_MINUTE = TICKS_PER_REAL_SECOND * 60L;

    /** 玩了几小时（整数部分）。负数当 0 —— 统计值不该是负的，但不必为此崩 */
    public static long playHours(long realTicks) {
        return Math.max(0, realTicks) / TICKS_PER_REAL_HOUR;
    }

    /** 玩了几小时【零几分】，0–59。是余数，不是总分钟数 */
    public static int playMinutes(long realTicks) {
        return (int) (Math.max(0, realTicks) / TICKS_PER_REAL_MINUTE % 60);
    }

    /** 同上，但输入是毫秒 —— 本次游玩时长按墙上时钟算，见 PlayTime 的注释 */
    public static long playHoursOfMillis(long millis) {
        return Math.max(0, millis) / 3_600_000L;
    }

    /** 同上，0–59 */
    public static int playMinutesOfMillis(long millis) {
        return (int) (Math.max(0, millis) / 60_000L % 60);
    }
}
