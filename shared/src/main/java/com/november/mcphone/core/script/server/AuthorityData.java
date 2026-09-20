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
 * 收款方/离线玩家也要查得到，所以不能挂在玩家身上（与 {@code EconomyData} 同一条理由）。
 *
 * <p>范围本步只支持两档：<b>所有人</b>（{@link #licenseAll}）与<b>指定玩家</b>（{@link #license}）。
 * "组"仓库里还没有概念，留后续步。
 *
 * <p>注意它<b>不是</b>能力勾选：能力在 {@link Deployment#approvedActions()} 里，运行时取交集
 * （{@link ServerAuthority}）。这里只回答"这个人有没有这个 App 的许可"。
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

    public void unlicenseAll(String appId) {
        boolean changed = everyone.remove(appId);
        changed |= players.remove(appId) != null;
        if (changed) setDirty();
    }

    /** 给指定玩家（离线也查得到 —— 记的是 UUID）。 */
    public void license(String appId, UUID player) {
        if (players.computeIfAbsent(appId, k -> new TreeSet<>()).add(player)) setDirty();
    }

    public void revoke(String appId, UUID player) {
        Set<UUID> set = players.get(appId);
        if (set != null && set.remove(player)) setDirty();
    }

    /** 这个人有没有这个 App 的许可。 */
    public boolean isLicensed(String appId, UUID player) {
        if (everyone.contains(appId)) return true;
        Set<UUID> set = players.get(appId);
        return set != null && set.contains(player);
    }

    /** 给 OP 命令/详情页看的一句话范围。 */
    public String scopeOf(String appId) {
        if (everyone.contains(appId)) return "所有人";
        Set<UUID> set = players.get(appId);
        return set == null || set.isEmpty() ? "无" : "指定玩家 " + set.size() + " 人";
    }

    // ---------------------------------------------------------------- 存档

    private static final String EVERYONE = "everyone";
    private static final String PLAYERS = "players";

    static AuthorityData load(CompoundTag tag) {
        AuthorityData d = new AuthorityData();
        ListTag all = tag.getList(EVERYONE, Tag.TAG_STRING);
        for (int i = 0; i < all.size(); i++) d.everyone.add(all.getString(i));
        ListTag ps = tag.getList(PLAYERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < ps.size(); i++) {
            CompoundTag c = ps.getCompound(i);
            Set<UUID> set = new TreeSet<>();
            ListTag ids = c.getList("uuids", Tag.TAG_STRING);
            for (int j = 0; j < ids.size(); j++) set.add(UUID.fromString(ids.getString(j)));
            d.players.put(c.getString("appId"), set);
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
