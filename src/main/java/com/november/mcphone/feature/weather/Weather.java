package com.november.mcphone.feature.weather;

/**
 * 天气判定 —— 现在到底是什么天，以及这种天适合干什么。
 *
 * ================================================================
 * 为什么"现在什么天"不是一句 isRaining() 就完了
 * ================================================================
 *
 * Minecraft 的天气是【全世界一个开关】，但落下来的东西按【当地生物群系】算。
 * 这两件事分开之后，同一个 isRaining() == true 在三个地方是三种天：
 *
 *   平原   下雨
 *   雪山   下雪        —— 同一个开关，落下来的是雪
 *   沙漠   什么都不落   —— 但天光照样暗下来
 *
 * 只看 isRaining() 的话，站在沙漠里的玩家会看到手机说"正在下雨"，而他抬头
 * 是大晴天。站在雪山上的会看到"雨"，而地上在积雪。
 *
 * 还有第四种：下界和末地【没有天气这回事】。那里 isRaining() 恒为 false，
 * 所以不会报错，只会一直说"晴"——而末地是一片虚空，说它"晴"很怪。
 *
 * ================================================================
 * 为什么单独一个类，而且【不 import 任何 Minecraft 类型】
 * ================================================================
 *
 * 上面那四种情况里，只有一种能在自己的存档里随手试出来。剩下三种要专门跑去
 * 沙漠、雪山、下界各站一次，而且还得等到在下雨——真去试一遍要半小时，还未必
 * 撞得上雷暴。
 *
 * 判错了也不会崩：手机只是在沙漠里说"下雨"。这种错误没有任何机制会告诉你。
 *
 * 所以判定逻辑摆在这里，用 javac 单独编出来把四种情况乘昼夜全跑一遍。与
 * {@link com.november.mcphone.feature.clock.WorldClock} 和
 * {@link com.november.mcphone.feature.clock.Greeting} 同一个考虑。
 */
public final class Weather {

    private Weather() {}

    /**
     * 当地会落下什么。
     *
     * 自己定一个枚举，而不是直接用 Biome.Precipitation：那个类型带着整个
     * Minecraft 一起进来，这个类就编不成单独的了。调用方负责把两者对上，
     * 那是一句 switch，出错也当场看得见。
     */
    public enum Precip {
        /** 这个群系不下任何东西（沙漠、恶地） */
        NONE,
        /** 下雨 */
        RAIN,
        /** 下雪 */
        SNOW
    }

    /**
     * 判出来的天。
     *
     * 每一项对应两个语言键：{@code mcphone.weather.kind.<suffix>} 是天气名，
     * {@code mcphone.weather.advice.<suffix>} 是"适合干什么"。
     */
    public enum Kind {
        /** 晴 */
        CLEAR("clear", true),
        /** 下雨 */
        RAIN("rain", false),
        /** 下雪 */
        SNOW("snow", false),
        /** 雷雨 */
        THUNDER("thunder", false),
        /** 别处在下雨，这里不下。天却是阴的 */
        DRY("dry", false),
        /** 这个维度没有天气（下界、末地） */
        NONE("none", false);

        private final String suffix;

        /** 昼夜两套说法。只有晴天需要——晴天白天出门，晴天夜里刷怪，是两件事 */
        private final boolean nightVariant;

        Kind(String suffix, boolean nightVariant) {
            this.suffix = suffix;
            this.nightVariant = nightVariant;
        }

        /** 天气名的语言键 */
        public String nameKey() {
            return "mcphone.weather.kind." + suffix;
        }

        /**
         * 这种天的图标，相对 assets/mcphone/textures/ 的路径（不含 .png）。
         *
         * 返回的是【字符串】不是 ResourceLocation：那个类型带着整个 Minecraft
         * 一起进来，这个类就编不成单独的了，断言也就跑不了。拼成完整路径
         * 是界面层一句话的事。
         */
        public String iconPath() {
            return "weather/" + suffix;
        }

        /**
         * "适合干什么"的语言键。
         *
         * 键由枚举拼出来，不散在调用点：拼错一个不会报错，只会把
         * "mcphone.weather.advice.thunber" 原样画在手机上。
         */
        public String adviceKey(boolean night) {
            return "mcphone.weather.advice." + suffix + (night && nightVariant ? ".night" : "");
        }

        public boolean hasNightVariant() {
            return nightVariant;
        }
    }

    // ============================================================
    //  判定
    // ============================================================

    /**
     * 现在是什么天。
     *
     * 顺序是有讲究的：
     *
     *   1. 没有天气的维度先出局    —— 下界不管别的字段说什么都是 NONE
     *   2. 没在下                  —— 晴
     *   3. 在打雷                  —— 雷雨。【排在当地降水之前】：沙漠里也
     *                                 会劈闪电，那是真的会烧东西、会把苦力怕
     *                                 劈成高压的，比"这儿不下雨"要紧得多
     *   4. 再看当地落什么           —— 雪 / 雨 / 什么都不落
     *
     * @param hasWeather  这个维度有没有天气（下界末地为 false）
     * @param raining     世界的下雨开关
     * @param thundering  世界的打雷开关
     * @param local       玩家脚下这个生物群系会落什么
     */
    public static Kind classify(boolean hasWeather, boolean raining,
                                boolean thundering, Precip local) {
        if (!hasWeather) return Kind.NONE;
        if (!raining) return Kind.CLEAR;
        if (thundering) return Kind.THUNDER;

        return switch (local == null ? Precip.NONE : local) {
            case SNOW -> Kind.SNOW;
            case RAIN -> Kind.RAIN;
            case NONE -> Kind.DRY;
        };
    }
}
