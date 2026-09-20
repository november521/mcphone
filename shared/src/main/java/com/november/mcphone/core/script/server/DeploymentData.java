package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.PhoneSavedData;
import com.november.mcphone.core.script.net.Handshake;
import com.november.mcphone.core.script.net.ScriptProtocol;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
 * <h2>容量与上限（对抗组 Q4）</h2>
 *
 * 持久层与线格式<b>同一套上限</b>（{@link ScriptProtocol}）：候选最多 {@link #MAX_CANDIDATES} 条（按入队时间
 * LRU 淘汰）、每条动作/能力 ≤ {@link Deployment#MAX_ACTIONS}、单元素 ≤ {@link Deployment#MAX_ID_LEN}、
 * 摘要一律 64 位小写 hex（{@link Deployment#validDigest}）。不校验的后果是"存得进去、握手推不出去"
 * （{@code writeUtf(s, 64)} 在编码期抛），那条路会推给所有客户端。
 *
 * <h2>坏条目不抛（对抗组 Q7）</h2>
 *
 * 读档时坏条目<b>跳过并 WARN</b>，绝不让 {@code load} 抛 —— 那会顺着 {@code computeIfAbsent} 冒到开服路径。
 */
public final class DeploymentData extends PhoneSavedData {

    static final String FILE_NAME = MCphone.MODID + "_script_deployments";

    /** 候选上限，与握手一条 begin 能带的最大部署数同量级。 */
    public static final int MAX_CANDIDATES = ScriptProtocol.HANDSHAKE_MAX_DEPLOYMENTS;

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

    /** 进候选队列。同一摘要重复放只留一份；条数超限按入队时间淘汰最早的。 */
    public boolean putCandidate(Candidate c) {
        if (c == null) return false;
        CompoundTag tag = c.toTag();
        CompoundTag old = candidates.get(c.packageDigest());
        if (tag.equals(old)) return false;
        candidates.put(c.packageDigest(), tag);
        evictCandidates();
        setDirty();
        return true;
    }

    private void evictCandidates() {
        while (candidates.size() > MAX_CANDIDATES) {
            String oldest = null;
            long at = Long.MAX_VALUE;
            for (Map.Entry<String, CompoundTag> e : candidates.entrySet()) {
                long q = e.getValue().getLong("queuedAt");
                if (q < at) {
                    at = q;
                    oldest = e.getKey();
                }
            }
            if (oldest == null) break;
            MCphone.LOGGER.warn("[MCphone] 候选队列超过 {} 条，淘汰最早入队的 {}（OP 一直没审）",
                    MAX_CANDIDATES, oldest);
            candidates.remove(oldest);
        }
    }

    public Candidate candidate(String packageDigest) {
        CompoundTag tag = candidates.get(packageDigest);
        return tag == null ? null : Candidate.fromTag(tag);
    }

    public List<Candidate> candidates() {
        List<Candidate> out = new ArrayList<>(candidates.size());
        for (CompoundTag tag : candidates.values()) {
            Candidate c = Candidate.fromTag(tag);
            if (c != null) out.add(c);
        }
        out.sort((a, b) -> a.appId().compareTo(b.appId()));
        return out;
    }

    // ---------------------------------------------------------------- 已批准

    /**
     * OP 批准一个候选：写下部署。
     *
     * <p><b>批准集合的默认值（对抗组 Q3）</b>：{@code approvedActions == null} = 显式"按声明全批"；
     * 传<b>空列表 = 什么都不批</b>（fail-closed）—— 原先"空 = 全批"是 fail-open，命令面/界面只要少收一次
     * 选择集就是静默全量授权。不在声明集合里的项被丢掉，并经 {@link Approval#droppedActions()} 回显给命令面。
     *
     * <p><b>装不进握手的部署在这里就拒</b>（定向对抗 ADV-S2b-5）：线上一条 deployment 的上限是
     * {@link ScriptProtocol#DATA_MAX}，超了就永远推不到客户端（表现是"本服没部署"），批准它
     * 只会制造一个查不出原因的假象。抛 {@link IllegalArgumentException}，命令面捕获后带字节数报错。
     */
    public Approval approve(Candidate c, List<String> approvedActions, List<String> approvedCapabilities,
                            UUID approver, long now) {
        List<String> actions = pick(approvedActions, c.declaredActions());
        List<String> caps = pick(approvedCapabilities, c.declaredCapabilities());
        List<String> droppedActions = dropped(approvedActions, c.declaredActions());
        List<String> droppedCaps = dropped(approvedCapabilities, c.declaredCapabilities());

        Deployment prev = deployment(c.appId());
        long approvalRevision = prev == null ? 1 : prev.approvalRevision() + 1;
        Deployment d = new Deployment(c.appId(), deploymentIdFor(c), c.packageDigest(), approvalRevision,
                c.packageDigest(), c.frontendDigest(), c.declaredActions(), actions,
                c.declaredCapabilities(), caps, approver, now);
        int wireBytes = Handshake.wireSize(d);
        if (wireBytes > ScriptProtocol.DATA_MAX) {
            throw new IllegalArgumentException("这条部署的握手表示 " + wireBytes + " 字节，超过单条上限 "
                    + ScriptProtocol.DATA_MAX + "（中文动作名按 3 字节/字符算）："
                    + "请减短动作名或减少动作数后再批准（否则客户端永远收不到这条部署）");
        }
        deployments.put(d.appId(), d.toTag());
        candidates.remove(c.packageDigest());
        setDirty();
        return new Approval(d, droppedActions, droppedCaps, prev != null);
    }

    /** null = 全批；空列表 = 一个都不批；其余取与声明的交集（保持请求顺序、去重）。 */
    private static List<String> pick(List<String> requested, List<String> declared) {
        if (requested == null) return List.copyOf(declared);
        List<String> out = new ArrayList<>();
        for (String w : requested) if (declared.contains(w) && !out.contains(w)) out.add(w);
        return out;
    }

    /** 请求里不在声明集合里的项 —— 回显给命令面，别静默吞掉。 */
    private static List<String> dropped(List<String> requested, List<String> declared) {
        if (requested == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String w : requested) if (!declared.contains(w) && !out.contains(w)) out.add(w);
        return out;
    }

    /** 坏档按"没有部署"处理（方向天然安全），绝不抛。 */
    public Deployment deployment(String appId) {
        CompoundTag tag = deployments.get(appId);
        if (tag == null) return null;
        try {
            Deployment d = Deployment.fromTag(tag);
            if (d == null) MCphone.LOGGER.warn("[MCphone] 部署 {} 读不出来（appId 不合法），按未部署处理", appId);
            return d;
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 部署 {} 读档案时出错，按未部署处理", appId, t);
            return null;
        }
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
        for (CompoundTag tag : deployments.values()) {
            Deployment d = Deployment.fromTag(tag);
            if (d != null) out.add(d);
        }
        out.sort((a, b) -> a.appId().compareTo(b.appId()));
        return out;
    }

    // ---------------------------------------------------------------- 工具

    /** {@code appId@摘要前 12 位}：展示/日志用。<b>不是身份</b>，任何判定都不许用它（appId 才是键）。 */
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
            Candidate candidate = Candidate.fromTag(c);
            if (candidate == null) {
                MCphone.LOGGER.warn("[MCphone] 候选 {} 读不出来，跳过", c.getString("packageDigest"));
                continue;
            }
            d.candidates.put(candidate.packageDigest(), c);
        }
        ListTag ds = tag.getList(DEPLOYMENTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < ds.size(); i++) {
            CompoundTag c = ds.getCompound(i);
            if (Deployment.fromTag(c) == null) {
                MCphone.LOGGER.warn("[MCphone] 部署 {} 读不出来，跳过", c.getString("appId"));
                continue;
            }
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

    /** 一次批准的结果：写的部署 + 被丢掉的请求项（回显给命令面）+ 是不是覆盖了旧部署。 */
    public record Approval(Deployment deployment, List<String> droppedActions, List<String> droppedCapabilities,
                           boolean replaced) {
        public Approval {
            droppedActions = List.copyOf(droppedActions);
            droppedCapabilities = List.copyOf(droppedCapabilities);
        }
    }

    /**
     * 一个待审候选（§14.3 ④）。字段就是 §14.4 里"部署需要的那几样"减去 OP 逐条勾选出来的那一半。
     *
     * <p>构造器做上限校验（摘要 64 hex、动作/能力 ≤32 且元素 ≤64）：这些值会进存档、也会进握手，
     * 必须在<b>入队</b>就挡住，而不是等编码期抛。读档走 {@link #fromTag}，坏条目返回 null 由调用方跳过。
     */
    public record Candidate(String appId, String packageDigest, String frontendDigest, long queuedAt,
                            List<String> declaredActions, List<String> declaredCapabilities) {

        public Candidate {
            if (!Deployment.validId(appId)) throw new IllegalArgumentException("appId 不合法：" + appId);
            if (!Deployment.validDigest(packageDigest)) {
                throw new IllegalArgumentException("包摘要必须是 64 位小写 hex：" + packageDigest);
            }
            if (!Deployment.validDigest(frontendDigest)) {
                throw new IllegalArgumentException("前端摘要必须是 64 位小写 hex：" + frontendDigest);
            }
            if (!Deployment.validList(declaredActions)) {
                throw new IllegalArgumentException("声明动作超限（≤" + Deployment.MAX_ACTIONS + " 条，单条 ≤"
                        + Deployment.MAX_ID_LEN + "）：" + declaredActions);
            }
            if (!Deployment.validList(declaredCapabilities)) {
                throw new IllegalArgumentException("声明能力超限：" + declaredCapabilities);
            }
            // S18：能力名必须对得上能力目录（§18.8）。对不上 = 服务端既不知道档位、也没法审批，
            // 一律在入队时拒（fail-closed）。App 自称什么档都不作数，名字本身必须是我们认得的。
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (String cap : declaredCapabilities) {
                if (!CapabilityCatalog.knownDeclared(cap)) {
                    throw new IllegalArgumentException("不认识的能力名（不在能力目录里）：" + cap);
                }
                if (!seen.add(cap)) {
                    throw new IllegalArgumentException("同一条能力声明了两次：" + cap);
                }
            }
            declaredActions = List.copyOf(declaredActions);
            declaredCapabilities = List.copyOf(declaredCapabilities);
        }

        CompoundTag toTag() {
            CompoundTag t = new CompoundTag();
            t.putString("appId", appId);
            t.putString("packageDigest", packageDigest);
            t.putString("frontendDigest", frontendDigest);
            t.putLong("queuedAt", queuedAt);
            Deployment.putList(t, "declaredActions", declaredActions);
            Deployment.putList(t, "declaredCapabilities", declaredCapabilities);
            return t;
        }

        /** 坏条目返回 null（不抛）—— 读档路径上必须活。 */
        static Candidate fromTag(CompoundTag t) {
            try {
                return new Candidate(t.getString("appId"), t.getString("packageDigest"), t.getString("frontendDigest"),
                        t.getLong("queuedAt"),
                        Deployment.getList(t, "declaredActions"), Deployment.getList(t, "declaredCapabilities"));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}
