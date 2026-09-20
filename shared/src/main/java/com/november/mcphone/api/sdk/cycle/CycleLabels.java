package com.november.mcphone.api.sdk.cycle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.WeekFields;

/**
 * 周期标签怎么算（施工方案 §23.3）。<b>纯计算，不碰 Minecraft，各实现共用这一份。</b>
 *
 * <h2>为什么算法要在 api 里而不是各自实现</h2>
 *
 * §23.3 要的不是"每个 App 都能算出一个周标签"，是"所有 App 算出<b>同一个</b>周标签"。
 * 各写各的话，两份代码在跨年那一周、在 {@code daily_at} 之前那几小时、在夏令时切换那天
 * 会给出不同答案，而那种不一致只在少数几天出现，测不出来、报不了错，
 * 表现为玩家的周限额莫名其妙多了一次或少了一次。
 *
 * <h2>周恒按 ISO，没有 weekly_on</h2>
 *
 * §23.3 同时写了 {@code weekly_on = "MONDAY"} 这个配置项和 {@code label('weekly') → "2026-W37"}
 * 这个格式，<b>这两条互相矛盾</b>：{@code 2026-W37} 是 ISO-8601 的周记法，而 ISO 周恒以周一开始。
 * 配 {@code weekly_on = "SUNDAY"} 的服务器会给出一个长得像 ISO、指的却不是那七天的字符串。
 * 所以砍掉 {@code weekly_on}：少一个配置项，少一个"标签相同但不是同一周"的来源。
 *
 * <h2>分界点</h2>
 *
 * <b>{@code daily_at} 之前算前一天</b>：04:00 分界、现在是 02:00，那还属于昨天那个周期。
 * 周与月的标签都按这个"周期日"算，不按日历日 —— 否则跨午夜那几小时里日标签和周标签会对不上。
 */
public final class CycleLabels {

    private CycleLabels() {
    }

    /** ISO 周：周一开始，每年第一周至少含 4 天。 */
    private static final WeekFields ISO = WeekFields.ISO;

    /**
     * 当前所属的周期日：把 {@code daily_at} 之前的时刻算进前一天。
     *
     * @param at      时刻
     * @param zone    服主配的时区（§23.3 必填，这里不替它兜默认值）
     * @param dailyAt 分界点，如 {@code 04:00}
     */
    public static LocalDate cycleDay(Instant at, ZoneId zone, LocalTime dailyAt) {
        ZonedDateTime z = at.atZone(zone);
        LocalDate d = z.toLocalDate();
        // Compare instants, not local clock text. During a fall-back overlap the local clock runs
        // 01:59 -> 01:00; a LocalTime comparison would make the label move backwards. atZone()
        // deliberately selects the earlier offset, so the boundary happens once and stays passed.
        Instant boundary = boundary(d, dailyAt, zone).toInstant();
        return at.isBefore(boundary) ? d.minusDays(1) : d;
    }

    /** 标签。格式是契约的一部分，改了等于把所有 App 的限量计数清零一次。 */
    public static String label(CycleKind kind, Instant at, ZoneId zone, LocalTime dailyAt) {
        LocalDate d = cycleDay(at, zone, dailyAt);
        return switch (kind) {
            case DAILY -> String.format("%04d-%02d-%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth());
            case WEEKLY -> weekLabel(d);
            case MONTHLY -> String.format("%04d-%02d", d.getYear(), d.getMonthValue());
        };
    }

    /**
     * {@code 2026-W37}。
     *
     * <p><b>年份取的是"这一周属于哪年"，不是这一天在哪年</b>：12 月 31 日常常落在下一年的第 1 周，
     * 写成 {@code 2026-W01} 的话，跨年那一周会和年初那一周撞成同一个标签 —— 限额被合并。
     */
    public static String weekLabel(LocalDate day) {
        return String.format("%04d-W%02d",
                day.get(ISO.weekBasedYear()), day.get(ISO.weekOfWeekBasedYear()));
    }

    /**
     * 下一个分界点的毫秒时间戳。{@link ITimeCycle#nextBoundary} 用它。
     *
     * <p><b>夏令时那天</b>：{@code daily_at} 落在被跳过的那一小时里时，
     * {@code atZone} 会把它顺到该时刻之后存在的第一瞬。那正是想要的 ——
     * 分界点晚来一小时，好过那一天整个没有分界点。
     */
    public static long nextBoundary(CycleKind kind, Instant at, ZoneId zone, LocalTime dailyAt) {
        LocalDate day = cycleDay(at, zone, dailyAt);
        LocalDate next = switch (kind) {
            case DAILY -> day.plusDays(1);
            case WEEKLY -> nextWeekStart(day);
            case MONTHLY -> day.withDayOfMonth(1).plusMonths(1);
        };
        // 分界点也在 dailyAt 这一刻：周期日是按它切的，边界与切法必须是同一个时刻
        return boundary(next, dailyAt, zone).toInstant().toEpochMilli();
    }

    private static ZonedDateTime boundary(LocalDate day, LocalTime dailyAt, ZoneId zone) {
        return LocalDateTime.of(day, dailyAt).atZone(zone);
    }

    private static LocalDate nextWeekStart(LocalDate day) {
        int ahead = (8 - day.getDayOfWeek().getValue()) % 7;
        return day.plusDays(ahead == 0 ? 7 : ahead);
    }
}
