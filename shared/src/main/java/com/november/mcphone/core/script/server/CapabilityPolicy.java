package com.november.mcphone.core.script.server;

import java.util.Set;

/**
 * 能力判定（S18，§18.8）：<b>档位查目录、开关看配置、批准看这个 App</b>。
 *
 * <p>三条都要过：
 * <ol>
 *   <li>{@code disabled}（服主开关，含 plain）→ {@code UNAVAILABLE}；</li>
 *   <li>{@code open}（首版有没有这条路径）与 {@code RESTRICTED}（首版一档全不开放）→ {@code UNAVAILABLE}；</li>
 *   <li>{@code GRANTED} 还要在<b>这个 App 的已批准集合</b>里 → 否则 {@code NOT_AUTHORIZED}；
 *       {@code PLAIN} 免审批。</li>
 * </ol>
 *
 * <p><b>App 自称什么档都不作数</b>：档位只来自 {@link CapabilityCatalog}；传入的"已批准集合"
 * 也只该来自服务端的 {@code Deployment.approvedCapabilities}。
 *
 * <p>线程：配置是 volatile 快照、判定无副作用，worker 上可以直接调；{@code reload} 只许在主线程。
 */
public final class CapabilityPolicy {

    /** 判定结果。 */
    public enum Verdict {
        OK,
        /** granted 档但这个 App 没被批。 */
        NOT_APPROVED,
        /** 服主全服关了（含 plain）。 */
        DISABLED,
        /** 目录里不开放（restricted 一档、或本版没有路径）。 */
        NOT_OPEN,
        /** 目录外的名字（不该发生：候选构造期已经拒过）。 */
        UNKNOWN
    }

    private volatile CapabilityConfig config;

    public CapabilityPolicy(CapabilityConfig config) {
        this.config = config;
    }

    public CapabilityConfig config() {
        return config;
    }

    /** 主线程：整份换新（{@code /mcphone script capabilities reload}）。 */
    public void reload(CapabilityConfig fresh) {
        this.config = fresh;
    }

    /**
     * @param approved 这个 App 的已批准能力（冻结集合，来自装配期的 Deployment）
     */
    public Verdict check(String capabilityId, Set<String> approved) {
        if (!CapabilityCatalog.knownDeclared(capabilityId)) return Verdict.UNKNOWN;
        CapabilityCatalog.Entry entry = CapabilityCatalog.of(capabilityId);
        // command.template:<id> 这类参数化族：目录里有登记，但首版不开放
        if (entry == null) return Verdict.NOT_OPEN;
        CapabilityConfig cfg = config;
        if (cfg != null && cfg.isDisabled(capabilityId)) return Verdict.DISABLED;
        if (!entry.open() || entry.tier() == CapabilityTier.RESTRICTED) return Verdict.NOT_OPEN;
        if (entry.tier() == CapabilityTier.PLAIN) return Verdict.OK;
        return approved != null && approved.contains(capabilityId) ? Verdict.OK : Verdict.NOT_APPROVED;
    }

    /** 拒绝时给脚本看的本地化键（不是文本）。 */
    public static String messageKey(Verdict verdict) {
        return switch (verdict) {
            case DISABLED -> "mcphone.script.capability.disabled";
            case NOT_APPROVED -> "mcphone.script.capability.not_approved";
            case NOT_OPEN -> "mcphone.script.capability.not_open";
            case UNKNOWN -> "mcphone.script.capability.unknown";
            case OK -> "";
        };
    }
}
