package com.november.mcphone.feature.weather;

import com.november.mcphone.feature.weather.Weather.Kind;
import com.november.mcphone.feature.weather.Weather.Precip;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Weather 的断言测试，用 javac 单独编，不需要 Minecraft。 */
public class WeatherTest {

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

    public static void main(String[] args) {
        theFourPlaces();
        noWeatherDimensionsWinOverEverything();
        thunderBeatsLocalPrecipitation();
        clearIgnoresBiome();
        nullPrecipIsSafe();
        adviceKeys();
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

    static void theFourPlaces() {
        eq(Weather.classify(true, true, false, Precip.RAIN), Kind.RAIN,
                "平原下雨 → 雨");
        eq(Weather.classify(true, true, false, Precip.SNOW), Kind.SNOW,
                "雪山下雨 → 下的是雪，不是雨");
        eq(Weather.classify(true, true, false, Precip.NONE), Kind.DRY,
                "沙漠下雨 → 什么都不落，是阴天不是雨天");
        eq(Weather.classify(false, false, false, Precip.NONE), Kind.NONE,
                "下界 → 没有天气这回事");
    }

    static void noWeatherDimensionsWinOverEverything() {
        for (boolean rain : new boolean[]{false, true})
            for (boolean thunder : new boolean[]{false, true})
                for (Precip p : Precip.values()) {
                    eq(Weather.classify(false, rain, thunder, p), Kind.NONE,
                            "没有天气的维度里 rain=" + rain + " thunder=" + thunder
                                    + " precip=" + p + " 仍该是 NONE");
                }
    }

    static void thunderBeatsLocalPrecipitation() {
        eq(Weather.classify(true, true, true, Precip.RAIN), Kind.THUNDER,
                "平原打雷 → 雷雨");
        eq(Weather.classify(true, true, true, Precip.NONE), Kind.THUNDER,
                "沙漠打雷仍是雷雨——闪电照劈");
        eq(Weather.classify(true, true, true, Precip.SNOW), Kind.THUNDER,
                "雪山打雷仍是雷雨");

        check(Weather.classify(true, true, true, Precip.NONE) != Kind.DRY,
                "打雷必须压过'这儿不下雨'");
    }

    static void clearIgnoresBiome() {
        for (Precip p : Precip.values()) {
            eq(Weather.classify(true, false, false, p), Kind.CLEAR,
                    "没下雨时 precip=" + p + " 也该是晴");
        }
        // 没下雨却打雷：原版不会出现，别的模组改天气时可能造出来
        eq(Weather.classify(true, false, true, Precip.RAIN), Kind.CLEAR,
                "没下雨却报打雷时，按晴处理");
    }

    static void nullPrecipIsSafe() {
        eq(Weather.classify(true, true, false, null), Kind.DRY,
                "拿不到生物群系时按'什么都不落'处理，不能抛异常");
        checks++;   // 上面这一行没抛出来本身就是一条断言
    }

    static void adviceKeys() {
        Set<String> nameKeys = new HashSet<>();
        Set<String> adviceKeys = new HashSet<>();

        for (Kind k : Kind.values()) {
            check(k.nameKey().startsWith("mcphone.weather.kind."), k + " 天气名键前缀不对");
            check(k.adviceKey(false).startsWith("mcphone.weather.advice."), k + " 建议键前缀不对");
            check(nameKeys.add(k.nameKey()), k + " 的天气名键与别人重复");
            adviceKeys.add(k.adviceKey(false));
            adviceKeys.add(k.adviceKey(true));

            check(k.nameKey().equals(k.nameKey().toLowerCase()), k + " 键里有大写");
        }

        eq(Kind.CLEAR.hasNightVariant(), true, "晴天要分昼夜——白天出门，夜里刷怪，是两件事");
        check(!Kind.CLEAR.adviceKey(true).equals(Kind.CLEAR.adviceKey(false)),
                "晴天昼夜两个键必须不同");
        for (Kind k : Kind.values()) {
            if (k == Kind.CLEAR) continue;
            eq(k.adviceKey(true), k.adviceKey(false), k + " 不分昼夜，两个键该一样");
        }

        // 6 种天气 + 晴天多一个夜里版 = 7 个建议键
        eq(adviceKeys.size(), 7, "建议键总数");
        eq(nameKeys.size(), Kind.values().length, "天气名键必须两两不同");
    }

    static void exhaustiveSweep() {
        int combos = 0;
        for (boolean has : new boolean[]{false, true})
            for (boolean rain : new boolean[]{false, true})
                for (boolean thunder : new boolean[]{false, true})
                    for (Precip p : new Precip[]{Precip.NONE, Precip.RAIN, Precip.SNOW, null}) {
                        Kind k = Weather.classify(has, rain, thunder, p);
                        checks++;
                        combos++;
                        if (k == null) {
                            failures.add("组合无解: has=" + has + " rain=" + rain
                                    + " thunder=" + thunder + " precip=" + p);
                        }
                    }
        System.out.println("  全量扫描：" + combos + " 种组合，每一种都判得出天气");
    }
}
