package com.november.mcphone.core.script.server.economy;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 原版计分板的门面（施工方案 §18.6 末尾点名的那个）。<b>1.20.1 这一份。</b>
 *
 * <p>与 {@code layers/version/1.20.3+/} 下那一份<b>对外签名逐字相同</b>，内部不同：
 * 这边是 {@code getOrCreatePlayerScore(String, Objective)} 返回 {@link Score}，
 * 1.20.3 起换成了 {@code ScoreHolder} → {@code ScoreAccess}。差异挡在门面里，
 * 共用代码两边用同一份。
 *
 * <p>其余说明（为什么按玩家名存、为什么只能在主线程调）见那一份的类注释，不在这儿复述。
 */
public final class Scores {

    private Scores() {
    }

    public static boolean hasObjective(MinecraftServer server, String objective) {
        return server.getScoreboard().hasObjective(objective);
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
        if (sb.hasObjective(objective)) return writable(server, objective);
        // DUMMY：只能由命令与代码改，不会被原版的统计规则自己写
        sb.addObjective(objective, ObjectiveCriteria.DUMMY, Component.literal(display),
                ObjectiveCriteria.RenderType.INTEGER);
        return sb.hasObjective(objective);
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
     * <p><b>读不许有副作用</b>：{@code getOrCreatePlayerScore} 会把这个人建进计分板，
     * 而这一版新建时还会 {@code setScore(0)} + {@code forceUpdate}，于是「查一次余额」
     * 就给全服发了一个分值同步包、并把存档标脏。先问 {@code hasPlayerScore}。
     */
    public static int get(MinecraftServer server, String objective, String holder) {
        Objective o = server.getScoreboard().getObjective(objective);
        if (o == null) return 0;
        if (!server.getScoreboard().hasPlayerScore(holder, o)) return 0;
        return server.getScoreboard().getOrCreatePlayerScore(holder, o).getScore();
    }

    public static void set(MinecraftServer server, String objective, String holder, int value) {
        Objective o = server.getScoreboard().getObjective(objective);
        if (o == null) return;
        server.getScoreboard().getOrCreatePlayerScore(holder, o).setScore(value);
    }

    /**
     * 对账用：这个 objective 上所有持有者的分值，<b>按持有者名排序</b>。
     *
     * <p>排序的理由见 {@code layers/version/1.20.3+/} 下那一份：原版两版给的次序不同，
     * 而门面返回的是 {@code LinkedHashMap}。
     *
     * <p><b>眼下没有调用方</b>：守恒对账（§22.10 / §22.12）要用它，但那条路还没接。
     */
    public static Map<String, Integer> all(MinecraftServer server, String objective) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Objective o = server.getScoreboard().getObjective(objective);
        if (o == null) return out;
        java.util.List<Score> entries =
                new java.util.ArrayList<>(server.getScoreboard().getPlayerScores(o));
        entries.sort(java.util.Comparator.comparing(Score::getOwner));
        for (Score s2 : entries) out.put(s2.getOwner(), s2.getScore());
        return out;
    }
}
