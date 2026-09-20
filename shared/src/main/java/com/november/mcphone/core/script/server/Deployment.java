package com.november.mcphone.core.script.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 本服对某个 App 的<b>已批准部署</b>（施工方案 §14.4）。这是服务端权威的那一份：
 * 玩家带过来的 {@code manifest.json} 只用于<b>提出申请</b>，运行时一律以本表为准（§13.6 第 6 行）。
 *
 * <p><b>两个动作集合要分开</b>：
 * <ul>
 *   <li>{@link #declaredActions()} —— 包里声明的动作全集。请求的 {@code actionId} 不在里面 → {@code NOT_DEPLOYED}
 *       （部署两轴都不在，连桶都不建）；</li>
 *   <li>{@link #approvedActions()} —— OP 逐条勾选批准的子集（∩ declared）。声明了但没批 → 过了部署闸、
 *       被授权层拒 → {@code NOT_AUTHORIZED}（§14.4「能力批准是逐条勾选，不是整包同意」）。</li>
 * </ul>
 *
 * <p>能力（capabilities）同理保留 declared/approved 两份；S17 只持久化与展示，真正的能力目录与执行是 S18。
 */
public record Deployment(
        String appId,
        String deploymentId,
        String revision,
        String packageDigest,
        String frontendDigest,
        List<String> declaredActions,
        List<String> approvedActions,
        List<String> declaredCapabilities,
        List<String> approvedCapabilities,
        UUID approver,
        long approvedAt) {

    public Deployment {
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
        t.putString(PACKAGE_DIGEST, packageDigest);
        t.putString(FRONTEND_DIGEST, frontendDigest);
        t.put(APPROVER, StringTag.valueOf(approver == null ? "" : approver.toString()));
        t.putLong(APPROVED_AT, approvedAt);
        putList(t, DECLARED_ACTIONS, declaredActions);
        putList(t, APPROVED_ACTIONS, approvedActions);
        putList(t, DECLARED_CAPS, declaredCapabilities);
        putList(t, APPROVED_CAPS, approvedCapabilities);
        return t;
    }

    public static Deployment fromTag(CompoundTag t) {
        String approver = t.getString(APPROVER);
        return new Deployment(
                t.getString(APP_ID),
                t.getString(DEPLOYMENT_ID),
                t.getString(REVISION),
                t.getString(PACKAGE_DIGEST),
                t.getString(FRONTEND_DIGEST),
                getList(t, DECLARED_ACTIONS),
                getList(t, APPROVED_ACTIONS),
                getList(t, DECLARED_CAPS),
                getList(t, APPROVED_CAPS),
                approver.isEmpty() ? null : UUID.fromString(approver),
                t.getLong(APPROVED_AT));
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
