package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.PhoneSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 本服的脚本部署表（施工方案 §14.3/§14.4）：<b>世界级存档</b>，随世界走（单人游戏换世界不串）。
 *
 * <ul>
 *   <li>{@link Candidate} —— 放进 {@code <世界>/mcphone/store/incoming/} 的包，解包 + 验签后进候选队列；
 *       它只是"申请"，<b>不给任何特权</b>。</li>
 *   <li>{@link Deployment} —— OP 批准之后写下的权威那一份。运行时只看它。</li>
 * </ul>
 *
 * <p><b>运行期不许改</b>：写只发生在开服扫描与 OP 命令里；请求路径上只读（与 {@code CurrencyRegistry}
 * 同一条纪律）。表本身是普通 {@code LinkedHashMap}，读依赖"服务端主线程 + 只在装配/命令时改"。
 */
public final class DeploymentData extends PhoneSavedData {

    static final String FILE_NAME = MCphone.MODID + "_script_deployments";

    /** 候选（待审）。键是包摘要。 */
    private final Map<String, CompoundTag> candidates = new LinkedHashMap<>();
    /** 已批准部署。键是 appId —— 一个 App 在本服只有一份当前部署。 */
    private final Map<String, CompoundTag> deployments = new LinkedHashMap<>();

    public DeploymentData() {
    }

    public static DeploymentData get(MinecraftServer server) {
        return getOrCreate(server, FILE_NAME, DeploymentData::new, DeploymentData::load);
    }

    // ---------------------------------------------------------------- 候选

    /** 进候选队列。同一摘要重复放只留一份；内容变了才算脏。 */
    public boolean putCandidate(Candidate c) {
        CompoundTag tag = c.toTag();
        CompoundTag old = candidates.get(c.packageDigest());
        if (tag.equals(old)) return false;
        candidates.put(c.packageDigest(), tag);
        setDirty();
        return true;
    }

    public Candidate candidate(String packageDigest) {
        CompoundTag tag = candidates.get(packageDigest);
        return tag == null ? null : Candidate.fromTag(tag);
    }

    public List<Candidate> candidates() {
        List<Candidate> out = new ArrayList<>(candidates.size());
        for (CompoundTag tag : candidates.values()) out.add(Candidate.fromTag(tag));
        out.sort((a, b) -> a.appId().compareTo(b.appId()));
        return out;
    }

    // ---------------------------------------------------------------- 已批准

    /**
     * OP 批准一个候选：写下部署。{@code approvedActions}/{@code approvedCapabilities} 必须是声明集合的
     * <b>子集</b>（多余的丢掉，不许凭空出现）；留空表示按声明全批。
     */
    public Deployment approve(Candidate c, List<String> approvedActions, List<String> approvedCapabilities,
                              UUID approver, long now) {
        List<String> actions = c.declaredActions().isEmpty()
                ? List.of()
                : subset(approvedActions.isEmpty() ? c.declaredActions() : approvedActions, c.declaredActions());
        List<String> caps = c.declaredCapabilities().isEmpty()
                ? List.of()
                : subset(approvedCapabilities.isEmpty() ? c.declaredCapabilities() : approvedCapabilities,
                        c.declaredCapabilities());
        Deployment d = new Deployment(c.appId(), deploymentIdFor(c), c.packageDigest(), c.packageDigest(),
                c.frontendDigest(), c.declaredActions(), actions,
                c.declaredCapabilities(), caps, approver, now);
        deployments.put(d.appId(), d.toTag());
        candidates.remove(c.packageDigest());
        setDirty();
        return d;
    }

    public Deployment deployment(String appId) {
        CompoundTag tag = deployments.get(appId);
        return tag == null ? null : Deployment.fromTag(tag);
    }

    /** 撤掉一个 App 的部署（OP 命令）。返回被撤的那一份，没有就是 null。 */
    public Deployment remove(String appId) {
        CompoundTag tag = deployments.remove(appId);
        if (tag == null) return null;
        setDirty();
        return Deployment.fromTag(tag);
    }

    public List<Deployment> deployments() {
        List<Deployment> out = new ArrayList<>(deployments.size());
        for (CompoundTag tag : deployments.values()) out.add(Deployment.fromTag(tag));
        out.sort((a, b) -> a.appId().compareTo(b.appId()));
        return out;
    }

    // ---------------------------------------------------------------- 工具

    private static List<String> subset(List<String> wanted, List<String> declared) {
        List<String> out = new ArrayList<>();
        for (String w : wanted) if (declared.contains(w) && !out.contains(w)) out.add(w);
        return out;
    }

    /** {@code appId@摘要前 12 位}：确定性、可读、撞不上（摘要本身就分得开）。 */
    static String deploymentIdFor(Candidate c) {
        return c.appId() + "@" + c.packageDigest().substring(0, Math.min(12, c.packageDigest().length()));
    }

    // ---------------------------------------------------------------- 存档

    private static final String CANDIDATES = "candidates";
    private static final String DEPLOYMENTS = "deployments";

    static DeploymentData load(CompoundTag tag) {
        DeploymentData d = new DeploymentData();
        ListTag cs = tag.getList(CANDIDATES, Tag.TAG_COMPOUND);
        for (int i = 0; i < cs.size(); i++) {
            CompoundTag c = cs.getCompound(i);
            d.candidates.put(c.getString("packageDigest"), c);
        }
        ListTag ds = tag.getList(DEPLOYMENTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < ds.size(); i++) {
            CompoundTag c = ds.getCompound(i);
            d.deployments.put(c.getString("appId"), c);
        }
        return d;
    }

    @Override
    protected CompoundTag write(CompoundTag tag) {
        ListTag cs = new ListTag();
        for (CompoundTag c : candidates.values()) cs.add(c);
        tag.put(CANDIDATES, cs);
        ListTag ds = new ListTag();
        for (CompoundTag c : deployments.values()) ds.add(c);
        tag.put(DEPLOYMENTS, ds);
        return tag;
    }

    /**
     * 一个待审候选（§14.3 ④）。字段就是 §14.4 里"部署需要的那几样"减去 OP 逐条勾选出来的那一半。
     */
    public record Candidate(String appId, String packageDigest, String frontendDigest,
                            List<String> declaredActions, List<String> declaredCapabilities) {

        public Candidate {
            declaredActions = List.copyOf(declaredActions);
            declaredCapabilities = List.copyOf(declaredCapabilities);
        }

        CompoundTag toTag() {
            CompoundTag t = new CompoundTag();
            t.putString("appId", appId);
            t.putString("packageDigest", packageDigest);
            t.putString("frontendDigest", frontendDigest);
            Deployment.putList(t, "declaredActions", declaredActions);
            Deployment.putList(t, "declaredCapabilities", declaredCapabilities);
            return t;
        }

        static Candidate fromTag(CompoundTag t) {
            return new Candidate(t.getString("appId"), t.getString("packageDigest"), t.getString("frontendDigest"),
                    Deployment.getList(t, "declaredActions"), Deployment.getList(t, "declaredCapabilities"));
        }
    }
}
