package com.november.mcphone.api.sdk.cycle;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 周期标签（施工方案 §23.3、§23.6）：所有 App 的"这一周"必须是同一周。
 *
 * <p>跑法：{@code ./gradlew assertTests}（在 {@code platforms/<目标名>/} 下）。
 */
public class TimeCycleTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(what + "  期望 " + expected + "，实际 " + actual);
        }
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    static final ZoneId NY = ZoneId.of("America/New_York");
    static final LocalTime FOUR = LocalTime.of(4, 0);

    static Instant at(ZoneId z, int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, z).toInstant();
    }

    static String label(CycleKind k, Instant t, ZoneId z) {
        return CycleLabels.label(k, t, z, FOUR);
    }

    /** §23.3 原文举的那三个例子。格式是契约，改了等于把所有 App 的限量清零一次。 */
    static void planExamples() {
        Instant t = at(SH, 2026, 9, 8, 12, 0);
        eq(label(CycleKind.DAILY, t, SH), "2026-09-08", "日标签");
        eq(label(CycleKind.WEEKLY, t, SH), "2026-W37", "周标签");
        eq(label(CycleKind.MONTHLY, t, SH), "2026-09", "月标签");
    }

    /** daily_at 默认 04:00 不是 00:00：之前那几小时还算前一天。 */
    static void boundaryAtFour() {
        eq(label(CycleKind.DAILY, at(SH, 2026, 9, 8, 2, 30), SH), "2026-09-07", "02:30 还算前一天");
        eq(label(CycleKind.DAILY, at(SH, 2026, 9, 8, 3, 59), SH), "2026-09-07", "03:59 还算前一天");
        eq(label(CycleKind.DAILY, at(SH, 2026, 9, 8, 4, 0), SH), "2026-09-08", "04:00 整换天");
        eq(label(CycleKind.DAILY, at(SH, 2026, 9, 8, 5, 30), SH), "2026-09-08", "05:30 是当天");

        // 周与月也按周期日算，不按日历日 —— 否则跨午夜那几小时里日标签和周标签会对不上
        eq(label(CycleKind.MONTHLY, at(SH, 2026, 10, 1, 2, 0), SH), "2026-09", "10-01 的 02:00 还算 9 月");
        eq(label(CycleKind.WEEKLY, at(SH, 2026, 9, 14, 2, 0), SH), "2026-W37", "周一 02:00 还算上一周");
    }

    /** 跨年那一周不许和年初那一周撞成同一个标签。 */
    static void yearBoundary() {
        String a = label(CycleKind.WEEKLY, at(SH, 2026, 12, 31, 12, 0), SH);
        String b = label(CycleKind.WEEKLY, at(SH, 2027, 1, 1, 12, 0), SH);
        eq(a, b, "12-31 与 01-01 在同一周，标签必须相同");
        eq(a, "2026-W53", "取的是这一周属于哪年，不是这一天在哪年");

        String c = label(CycleKind.WEEKLY, at(SH, 2027, 1, 5, 12, 0), SH);
        check(!c.equals(a), "下一周必须换标签：" + a + " vs " + c);
    }

    /** 改服务器时区 → 分界点跟着变，这正是 §23.3 要时区必填的理由。 */
    static void timezoneMoves() {
        Instant t = at(SH, 2026, 9, 8, 5, 30);
        eq(label(CycleKind.DAILY, t, SH), "2026-09-08", "上海看是 9-8");
        eq(label(CycleKind.DAILY, t, NY), "2026-09-07", "同一时刻纽约看是 9-7");
        check(!label(CycleKind.DAILY, t, SH).equals(label(CycleKind.DAILY, t, NY)),
                "两个时区必须给出不同的日标签");
    }

    /** 两个 App 各问各的，只要配置一样就必须落在同一个标签上。 */
    static void twoAppsAgree() {
        // 同一毫秒里两次调用
        Instant t = at(SH, 2026, 9, 10, 23, 59);
        eq(label(CycleKind.WEEKLY, t, SH), label(CycleKind.WEEKLY, t, SH), "同一时刻两次调用一致");

        // 相差几秒的两次调用，只要没跨分界点就还是同一周
        Instant t2 = t.plusSeconds(30);
        eq(label(CycleKind.WEEKLY, t, SH), label(CycleKind.WEEKLY, t2, SH), "相差 30 秒仍是同一周");
    }

    /** 分界点必须落在 daily_at 那一刻：周期日按它切，边界与切法要是同一个时刻。 */
    static void boundaries() {
        Instant t = at(SH, 2026, 9, 8, 2, 30);
        long nb = CycleLabels.nextBoundary(CycleKind.DAILY, t, SH, FOUR);
        eq(Instant.ofEpochMilli(nb), at(SH, 2026, 9, 8, 4, 0), "02:30 的下一个日分界是当天 04:00");

        long nb2 = CycleLabels.nextBoundary(CycleKind.DAILY, at(SH, 2026, 9, 8, 5, 0), SH, FOUR);
        eq(Instant.ofEpochMilli(nb2), at(SH, 2026, 9, 9, 4, 0), "05:00 的下一个日分界是次日 04:00");

        long nw = CycleLabels.nextBoundary(CycleKind.WEEKLY, at(SH, 2026, 9, 8, 5, 0), SH, FOUR);
        eq(Instant.ofEpochMilli(nw), at(SH, 2026, 9, 14, 4, 0), "周分界是下周一 04:00");

        long nm = CycleLabels.nextBoundary(CycleKind.MONTHLY, at(SH, 2026, 9, 8, 5, 0), SH, FOUR);
        eq(Instant.ofEpochMilli(nm), at(SH, 2026, 10, 1, 4, 0), "月分界是下月 1 号 04:00");

        // 跨过分界点之后标签必须真的变了，不是差一秒还在原地
        String before = label(CycleKind.DAILY, at(SH, 2026, 9, 8, 3, 59), SH);
        String after = label(CycleKind.DAILY, Instant.ofEpochMilli(nb), SH);
        check(!before.equals(after), "跨过分界点标签要变：" + before + " → " + after);
    }

    /** 时间戳是毫秒，脚本侧要按字符串过 —— 这里只钉住它确实超过 2^53 那个量级。 */
    static void millisExceedSafeInteger() {
        long nb = CycleLabels.nextBoundary(CycleKind.DAILY, at(SH, 2026, 9, 8, 5, 0), SH, FOUR);
        check(nb > 1_000_000_000_000L, "毫秒时间戳是 13 位，早超过 JS 的安全整数位宽概念上限，实际值 " + nb);
    }

    static void kindKeys() {
        eq(CycleKind.of("daily"), CycleKind.DAILY, "daily");
        eq(CycleKind.of("weekly"), CycleKind.WEEKLY, "weekly");
        eq(CycleKind.of("monthly"), CycleKind.MONTHLY, "monthly");
        eq(CycleKind.of("yearly"), null, "认不出返回 null，不替调用方猜一个");
        eq(CycleKind.of(null), null, "null 也返回 null");
    }

    /** 夏令时那天 daily_at 落在被跳过的一小时里：分界点顺到它之后存在的第一瞬，而不是消失。 */
    static void dst() {
        ZoneId ny = ZoneId.of("America/New_York");
        LocalTime half = LocalTime.of(2, 30);   // 2027-03-14 的 02:30 在纽约不存在
        Instant before = ZonedDateTime.of(2027, 3, 13, 12, 0, 0, 0, ny).toInstant();
        long nb = CycleLabels.nextBoundary(CycleKind.DAILY, before, ny, half);
        ZonedDateTime z = Instant.ofEpochMilli(nb).atZone(ny);
        eq(z.toLocalDate().toString(), "2027-03-14", "分界点还在那一天");
        check(z.toLocalTime().getHour() == 3, "02:30 不存在，顺到 03:30，实际 " + z.toLocalTime());

        // 那一天照样算得出标签，没有"这一天不存在"
        Instant on = ZonedDateTime.of(2027, 3, 14, 12, 0, 0, 0, ny).toInstant();
        eq(CycleLabels.label(CycleKind.DAILY, on, ny, half), "2027-03-14", "夏令时那天的日标签");

        // 2026-11-01 01:30 occurs twice. The label may advance at either occurrence, but it must
        // never move backwards during the repeated hour and nextBoundary must stay in the future.
        LocalTime overlapBoundary = LocalTime.of(1, 30);
        var offsets = ny.getRules().getValidOffsets(java.time.LocalDateTime.of(2026, 11, 1, 1, 15));
        Instant first115 = java.time.LocalDateTime.of(2026, 11, 1, 1, 15)
                .atOffset(offsets.get(0)).toInstant();
        Instant first145 = java.time.LocalDateTime.of(2026, 11, 1, 1, 45)
                .atOffset(offsets.get(0)).toInstant();
        Instant second115 = java.time.LocalDateTime.of(2026, 11, 1, 1, 15)
                .atOffset(offsets.get(1)).toInstant();
        Instant second145 = java.time.LocalDateTime.of(2026, 11, 1, 1, 45)
                .atOffset(offsets.get(1)).toInstant();
        List<Instant> timeline = List.of(first115, first145, second115, second145);
        List<String> labels = timeline.stream()
                .map(i -> CycleLabels.label(CycleKind.DAILY, i, ny, overlapBoundary)).toList();
        eq(labels, List.of("2026-10-31", "2026-11-01", "2026-11-01", "2026-11-01"),
                "夏令时回拨小时标签只前进一次");
        for (Instant instant : timeline) {
            check(CycleLabels.nextBoundary(CycleKind.DAILY, instant, ny, overlapBoundary)
                            > instant.toEpochMilli(),
                    "重叠小时的 nextBoundary 必须在未来：" + instant);
        }
    }

    /** 周恒按 ISO：没有 weekly_on 这个配置，所以不存在"标签相同但不是同一周"。 */
    static void isoOnly() {
        // 周日与紧接的周一必须分属两个标签（ISO 周一开周）
        String sun = label(CycleKind.WEEKLY, at(SH, 2026, 9, 13, 12, 0), SH);
        String mon = label(CycleKind.WEEKLY, at(SH, 2026, 9, 14, 12, 0), SH);
        eq(sun, "2026-W37", "周日属于上一周");
        eq(mon, "2026-W38", "周一开新的一周");
        check(!sun.equals(mon), "ISO 周在周一换标签");
    }

    public static void main(String[] args) {
        dst();
        isoOnly();
        planExamples();
        boundaryAtFour();
        yearBoundary();
        timezoneMoves();
        twoAppsAgree();
        boundaries();
        millisExceedSafeInteger();
        kindKeys();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
