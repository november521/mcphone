package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 本服对某个 App 的<b>已批准部署</b>（施工方案 §14.4）。运行时只看它，玩家带的 {@code manifest.json}
 * 只用于提出申请（§13.6 第 6 行）。
 *
 * <h2>两条轴，两个字段（对抗组 Q2）</h2>
 *
 * <ul>
 *   <li>{@link #revision()} —— <b>包轴</b>，就是 {@code packageDigest}。客户端 {@code ScriptRpc.deployRev}
 *       与服务端 {@code DeploymentView.deployRev} 用它对齐："你手里的包 == 服务端批准的那个包"。</li>
 *   <li>{@link #approvalRevision()} —— <b>批准轴</b>，同一 App 每次重新批准 +1（单调）。包摘要不变、
 *       只改批准集合时它会动，用来区分"包没换、但权限变了"。<b>不参与</b> deployRev 对齐。</li>
 * </ul>
 *
 * <h2>两个动作集合要分开</h2>
 *
 * {@link #declaredActions()} 是包里声明的全集（不在里面 → {@code NOT_DEPLOYED}，连桶都不建）；
 * {@link #approvedActions()} 是 OP 逐条勾选批准的子集（声明了但没批 → 过部署闸、被授权层拒 → {@code NOT_AUTHORIZED}）。
 *
 * <p>{@code deploymentId} 只用于展示与日志（{@code appId@摘要前 12}）——<b>不是身份，任何判定都不许用它</b>。
 */
public record Deployment(
        String appId,
        String deploymentId,
        String revision,
        long approvalRevision,
        String packageDigest,
        String frontendDigest,
        List<String> declaredActions,
        List<String> approvedActions,
        List<String> declaredCapabilities,
        List<String> approvedCapabilities,
        UUID approver,
        long approvedAt) {

    /** 与线格式同一套上限：握手一条部署最多推这么多动作（{@code ScriptProtocol.MAX_ACTIONS_PER_DEPLOYMENT}）。 */
    public static final int MAX_ACTIONS = 32;
    /** 声明/批准集合里单个元素的长度上限，取 {@code ScriptProtocol.ID_MAX}。 */
    public static final int MAX_ID_LEN = 64;
    /** 摘要一律是裸小写 SHA-256 hex，64 字符。 */
    public static final int DIGEST_LEN = 64;

    public Deployment {
        // M5/C13：revision 与 packageDigest 是同一个值，构造时就归一 —— 只有一个源，永远不会分叉
        if (packageDigest != null && !packageDigest.equals(revision)) revision = packageDigest;
        declaredActions = List.copyOf(declaredActions);
        approvedActions = List.copyOf(approvedActions);
        declaredCapabilities = List.copyOf(declaredCapabilities);
        approvedCapabilities = List.copyOf(approvedCapabilities);
    }

    /** OP 批没批这个动作。 */
    public boolean approves(String actionId) {
        return approvedActions.contains(actionId);
    }

    // ---------------------------------------------------------------- NBT

    private static final String APP_ID = "appId";
    private static final String DEPLOYMENT_ID = "deploymentId";
    private static final String REVISION = "revision";
    private static final String APPROVAL_REVISION = "approvalRevision";
    private static final String PACKAGE_DIGEST = "packageDigest";
    private static final String FRONTEND_DIGEST = "frontendDigest";
    private static final String DECLARED_ACTIONS = "declaredActions";
    private static final String APPROVED_ACTIONS = "approvedActions";
    private static final String DECLARED_CAPS = "declaredCapabilities";
    private static final String APPROVED_CAPS = "approvedCapabilities";
    private static final String APPROVER = "approver";
    private static final String APPROVED_AT = "approvedAt";

    public CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        t.putString(APP_ID, appId);
        t.putString(DEPLOYMENT_ID, deploymentId);
        t.putString(REVISION, revision);
        t.putLong(APPROVAL_REVISION, approvalRevision);
        t.putString(PACKAGE_DIGEST, packageDigest);
        t.putString(FRONTEND_DIGEST, frontendDigest);
        t.putString(APPROVER, approver == null ? "" : approver.toString());
        t.putLong(APPROVED_AT, approvedAt);
        putList(t, DECLARED_ACTIONS, declaredActions);
        putList(t, APPROVED_ACTIONS, approvedActions);
        putList(t, DECLARED_CAPS, declaredCapabilities);
        putList(t, APPROVED_CAPS, approvedCapabilities);
        return t;
    }

    /**
     * 坏条目<b>不抛</b>：appId 空、结构不成立就返回 null（读不到 = 这个 App 没部署，方向天然安全）。
     * 批准人那一串解析不了时只丢批准人，不丢整条部署（对抗组 Q7）。
     */
    static Deployment fromTag(CompoundTag t) {
        String appId = t.getString(APP_ID);
        if (!validId(appId)) return null;
        UUID approver = null;
        String raw = t.getString(APPROVER);
        if (!raw.isEmpty()) {
            try {
                approver = UUID.fromString(raw);
            } catch (IllegalArgumentException e) {
                MCphone.LOGGER.warn("[MCphone] 部署 {} 的批准人 UUID 读不出来（{}），按无批准人处理", appId, raw);
            }
        }
        String packageDigest = t.getString(PACKAGE_DIGEST);
        String storedRevision = t.getString(REVISION);
        // M5：revision 与 packageDigest 是同一个值。读档一律以 packageDigest 为准，两边不一致就告警并归一 ——
        // 否则将来某一步只改一个字段，客户端的 deployRev 比对与"装配的是哪份代码"会静默分叉。
        if (!storedRevision.isEmpty() && !storedRevision.equals(packageDigest)) {
            MCphone.LOGGER.warn("[MCphone] 部署 {} 的 revision（{}）与包摘要不一致，按包摘要归一",
                    appId, storedRevision);
        }
        return new Deployment(appId, t.getString(DEPLOYMENT_ID), packageDigest,
                t.getLong(APPROVAL_REVISION), packageDigest, t.getString(FRONTEND_DIGEST),
                cleanList(t, DECLARED_ACTIONS), cleanList(t, APPROVED_ACTIONS),
                cleanList(t, DECLARED_CAPS), cleanList(t, APPROVED_CAPS), approver, t.getLong(APPROVED_AT));
    }

    // ---------------------------------------------------------------- 校验与列表

    /** 合法 id：非空、≤ {@link #MAX_ID_LEN}、不含控制字符。 */
    public static boolean validId(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_ID_LEN) return false;
        for (int i = 0; i < s.length(); i++) if (Character.isISOControl(s.charAt(i))) return false;
        return true;
    }

    /** 合法摘要：正好 64 个字符、全是小写 hex。 */
    public static boolean validDigest(String s) {
        if (s == null || s.length() != DIGEST_LEN) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** 列表上限与元素上限；坏的（超限/空元素）直接判不合法，交给调用方拒绝。 */
    public static boolean validList(List<String> values) {
        if (values.size() > MAX_ACTIONS) return false;
        for (String v : values) if (!validId(v)) return false;
        return true;
    }

    /** 读列表时逐条过滤，坏的<b>跳过</b>（不抛、不把整条部署带下水）。 */
    private static List<String> cleanList(CompoundTag t, String key) {
        List<String> out = new ArrayList<>();
        for (String v : getList(t, key)) {
            if (!validId(v)) {
                MCphone.LOGGER.warn("[MCphone] 部署里的 {} 有一条读不出来（长度 {}），跳过", key, v.length());
                continue;
            }
            if (out.size() >= MAX_ACTIONS) break;
            out.add(v);
        }
        return out;
    }

    static void putList(CompoundTag t, String key, List<String> values) {
        ListTag list = new ListTag();
        for (String v : values) list.add(StringTag.valueOf(v));
        t.put(key, list);
    }

    static List<String> getList(CompoundTag t, String key) {
        ListTag list = t.getList(key, Tag.TAG_STRING);
        List<String> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) out.add(list.getString(i));
        return out;
    }
}
