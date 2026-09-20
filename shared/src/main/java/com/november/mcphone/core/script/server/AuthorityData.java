package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.PhoneSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 授权表（施工方案 §13.2/§14.4）：谁可以用哪个 App。<b>世界级存档、按 UUID 记</b> ——
 * 离线玩家也要查得到，不能挂在玩家身上（与 {@code EconomyData} 同一条理由）。
 *
 * <p>范围只支持两档：<b>所有人</b>（{@link #licenseAll}）与<b>指定玩家</b>（{@link #license}）。"组"留后续。
 *
 * <h2>两个撤销的语义是分开的（对抗组 Q6）</h2>
 *
 * <ul>
 *   <li>{@link #unlicenseAll} 与 {@link #licenseAll} <b>对称</b>：只动"所有人"那一档，
 *       不碰指定名单（原先它顺手把指定名单也清了，而名字看不出来）。</li>
 *   <li>{@link #clearApp} 才是"这个 App 的授权全清"（所有人档 + 指定名单）。</li>
 * </ul>
 *
 * <p>它不是能力勾选：能力在 {@link Deployment#approvedActions()} 里，运行时取交集（{@link ServerAuthority}）。
 * <b>只在服务端主线程用</b>；请求路径只读，写只发生在 OP 命令里。
 */
public final class AuthorityData extends PhoneSavedData {

    static final String FILE_NAME = MCphone.MODID + "_script_authority";

    /** 对所有人放行的 App。 */
    private final Set<String> everyone = new TreeSet<>();
    /** App → 被单独授权的玩家。 */
    private final Map<String, Set<UUID>> players = new LinkedHashMap<>();

    public AuthorityData() {
    }

    public static AuthorityData get(MinecraftServer server) {
        return getOrCreate(server, FILE_NAME, AuthorityData::new, AuthorityData::load);
    }

    /** 给所有人。 */
    public void licenseAll(String appId) {
        if (everyone.add(appId)) setDirty();
    }

    /** 撤销"所有人"那一档。<b>不动指定名单</b>（要全清用 {@link #clearApp}）。返回有没有改动。 */
    public boolean unlicenseAll(String appId) {
        if (!everyone.remove(appId)) return false;
        setDirty();
        return true;
    }

    /** 这个 App 的授权全清：所有人档 + 指定名单。返回被清掉的指定玩家数。 */
    public int clearApp(String appId) {
        boolean changed = everyone.remove(appId);
        Set<UUID> removed = players.remove(appId);
        if (changed || removed != null) setDirty();
        return removed == null ? 0 : removed.size();
    }

    /** 给指定玩家（离线也查得到 —— 记的是 UUID）。 */
    public void license(String appId, UUID player) {
        if (players.computeIfAbsent(appId, k -> new TreeSet<>()).add(player)) setDirty();
    }

    /** 从这个 App 的指定名单里去掉一个玩家。返回有没有改动。 */
    public boolean revoke(String appId, UUID player) {
        Set<UUID> set = players.get(appId);
        if (set == null || !set.remove(player)) return false;
        setDirty();
        return true;
    }

    /** 这个 App 现在是不是"所有人"档 —— 命令面据此提示"要收紧先取消所有人档"。 */
    public boolean isEveryone(String appId) {
        return everyone.contains(appId);
    }

    /** 这个 App 的指定名单有几人。 */
    public int playerCount(String appId) {
        Set<UUID> set = players.get(appId);
        return set == null ? 0 : set.size();
    }

    /** 这个人有没有这个 App 的许可。 */
    public boolean isLicensed(String appId, UUID player) {
        if (everyone.contains(appId)) return true;
        Set<UUID> set = players.get(appId);
        return set != null && set.contains(player);
    }

    /** 给 OP 命令/详情页看的一句话范围。<b>两档都报</b>，不拿"所有人"盖住指定名单。 */
    public String scopeOf(String appId) {
        StringBuilder sb = new StringBuilder();
        sb.append(everyone.contains(appId) ? "所有人" : "未对所有人");
        int n = playerCount(appId);
        if (n > 0) sb.append("；另指定 ").append(n).append(" 人");
        return sb.toString();
    }

    // ---------------------------------------------------------------- 存档

    private static final String EVERYONE = "everyone";
    private static final String PLAYERS = "players";

    /** 坏 UUID <b>跳过并 WARN</b>，绝不从 load 抛（对抗组 Q7：这条在装配路径上）。 */
    static AuthorityData load(CompoundTag tag) {
        AuthorityData d = new AuthorityData();
        ListTag all = tag.getList(EVERYONE, Tag.TAG_STRING);
        for (int i = 0; i < all.size(); i++) {
            String id = all.getString(i);
            if (!Deployment.validId(id)) continue;
            d.everyone.add(id);
        }
        ListTag ps = tag.getList(PLAYERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < ps.size(); i++) {
            CompoundTag c = ps.getCompound(i);
            String appId = c.getString("appId");
            if (!Deployment.validId(appId)) {
                MCphone.LOGGER.warn("[MCphone] 授权表里有一条 appId 读不出来，跳过");
                continue;
            }
            Set<UUID> set = new TreeSet<>();
            ListTag ids = c.getList("uuids", Tag.TAG_STRING);
            for (int j = 0; j < ids.size(); j++) {
                String raw = ids.getString(j);
                try {
                    set.add(UUID.fromString(raw));
                } catch (IllegalArgumentException e) {
                    MCphone.LOGGER.warn("[MCphone] 授权表 {} 里的玩家 UUID 读不出来（{}），跳过", appId, raw);
                }
            }
            if (!set.isEmpty()) d.players.put(appId, set);
        }
        return d;
    }

    @Override
    protected CompoundTag write(CompoundTag tag) {
        ListTag all = new ListTag();
        for (String id : everyone) all.add(StringTag.valueOf(id));
        tag.put(EVERYONE, all);
        ListTag ps = new ListTag();
        for (Map.Entry<String, Set<UUID>> e : players.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("appId", e.getKey());
            ListTag ids = new ListTag();
            for (UUID u : e.getValue()) ids.add(StringTag.valueOf(u.toString()));
            c.put("uuids", ids);
            ps.add(c);
        }
        tag.put(PLAYERS, ps);
        return tag;
    }
}
