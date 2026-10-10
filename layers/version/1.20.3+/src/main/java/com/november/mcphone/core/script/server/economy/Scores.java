package com.november.mcphone.core.script.server.economy;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 原版计分板的门面（施工方案 §18.6 末尾点名的那个）。<b>1.20.3 起的那套 API。</b>
 *
 * <h2>门面为什么不把 ScoreAccess / ScoreHolder 透出去</h2>
 *
 * 透出去就等于把版本差异漏进调用方：{@link ScoreHolder} 与 {@link net.minecraft.world.scores.ScoreAccess}
 * 是 1.20.3 才有的，1.20.1 那边是 {@code getOrCreatePlayerScore(String, Objective)} 返回
 * {@code Score}。共用代码一旦 import 到它们，1.20.1 目标当场编不过，
 * 而那正是这个门面要挡住的事。
 *
 * <p>所以对外只有字符串、int 与 UUID —— 这几样在两边逐字相同。
 *
 * <h2>持有者用的是玩家名，不是 UUID</h2>
 *
 * §22.7 这一档的卖点是「服主用 {@code /scoreboard} 就能看和改」。按 UUID 存的话，
 * 服主要敲 {@code /scoreboard players get 069a79f4-44e9-4726-a5be-fca90e38aaf5 …} ——
 * 那就不是给人用的了。代价是改名会丢账，写在 {@link ScoreboardProvider} 的类注释里。
 *
 * <p><b>全部方法只在服务端主线程调</b>：{@link Scoreboard} 不是线程安全的。
 * 谁来保证在主线程上，是 {@link ScoreboardProvider} 的事。
 */
public final class Scores {

    private Scores() {
    }

    public static boolean hasObjective(MinecraftServer server, String objective) {
        return server.getScoreboard().getObjective(objective) != null;
    }

    /**
     * 这个 objective 能不能写。<b>只读 criteria 的一律不能</b>。
     *
     * <p>服主要是先敲过 {@code /scoreboard objectives add mcphone_eco_coin health}，
     * 这个 objective 就已经存在、而且是只读的。两个版本在这种 objective 上的行为<b>相反</b>：
     * 1.20.1 的 {@code Score.setScore} 不做任何检查、<b>静默写进去</b>（而原版每 tick 会覆盖它），
     * 1.20.3+ 的 {@code ScoreAccess.set} 第一句就抛 {@code IllegalStateException}。
     * 所以这一道必须在门面里判掉，两边才是同一个行为：报「用不了」。
     */
    public static boolean writable(MinecraftServer server, String objective) {
        Objective o = server.getScoreboard().getObjective(objective);
        return o != null && !o.getCriteria().isReadOnly();
    }

    /** 没有就建一个。建不出来、或者已经存在但只读，返回 false —— 调用方据此报 UNAVAILABLE。 */
    public static boolean ensureObjective(MinecraftServer server, String objective, String display) {
        Scoreboard sb = server.getScoreboard();
        if (sb.getObjective(objective) != null) return writable(server, objective);
        // DUMMY：只能由命令与代码改，不会被原版的统计规则自己写。
        // 【displayAutoUpdate 传 false】——原版 /scoreboard objectives add 传的就是 false
        // （实测其字节码是 iconst_0）。传 true 的话每次 set 都会把这一条的显示名强行写成
        // 朴素玩家名，把服主用 /scoreboard players display name 设过的文本覆盖掉，
        // 而这一档的全部卖点就是不跟服主的操作打架。NumberFormat 传 null 是原版的常态。
        sb.addObjective(objective, ObjectiveCriteria.DUMMY, Component.literal(display),
                ObjectiveCriteria.RenderType.INTEGER, false, null);
        return sb.getObjective(objective) != null;
    }

    /**
     * UUID → 计分板上的持有者名。<b>查不到返回 null</b>（调用方报 UNAVAILABLE）。
     *
     * <p>离线玩家走档案缓存 —— 从没上过线的人查不到，那是事实，不该编一个名字出来。
     */
    public static String nameOf(MinecraftServer server, UUID player) {
        var online = server.getPlayerList().getPlayer(player);
        if (online != null) return online.getScoreboardName();
        return com.november.mcphone.platform.PlayerAccess.cachedName(server,player).orElse(null);
    }

    /**
     * objective 不存在、或者这个人还没有分值时返回 0。
     *
     * <p><b>读不许有副作用</b>：{@code getOrCreatePlayerScore} 顾名思义会把这个人建进计分板，
     * 于是「查一次余额」就把从没交易过的玩家写进了存档、出现在 {@code /scoreboard players list} 里。
     * 这里走只读的那条。
     */
    public static int get(MinecraftServer server, String objective, String holder) {
        Objective o = server.getScoreboard().getObjective(objective);
        if (o == null) return 0;
        var info = server.getScoreboard().getPlayerScoreInfo(ScoreHolder.forNameOnly(holder), o);
        return info == null ? 0 : info.value();
    }

    public static void set(MinecraftServer server, String objective, String holder, int value) {
        Objective o = server.getScoreboard().getObjective(objective);
        if (o == null) return;
        server.getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(holder), o).set(value);
    }

    /**
     * 对账用：这个 objective 上所有持有者的分值，<b>按持有者名排序</b>。
     *
     * <p>排序不是装饰：原版这两版给的顺序不一样（1.20.1 的 {@code getPlayerScores} 末尾按
     * 分值排过，1.20.3+ 的 {@code listPlayerScores} 直接给哈希序），而门面返回的是
     * {@code LinkedHashMap} —— 不排的话同一份账在两个版本上迭代出两种次序。
     *
     * <p><b>眼下没有调用方</b>：守恒对账（§22.10 / §22.12）要用它，但那条路还没接。
     */
    public static Map<String, Integer> all(MinecraftServer server, String objective) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Objective o = server.getScoreboard().getObjective(objective);
        if (o == null) return out;
        java.util.List<PlayerScoreEntry> entries =
                new java.util.ArrayList<>(server.getScoreboard().listPlayerScores(o));
        entries.sort(java.util.Comparator.comparing(PlayerScoreEntry::owner));
        for (PlayerScoreEntry e : entries) out.put(e.owner(), e.value());
        return out;
    }
}
