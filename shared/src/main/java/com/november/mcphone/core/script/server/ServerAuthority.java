package com.november.mcphone.core.script.server;

import java.util.UUID;

/**
 * {@link AuthorityView} 的生产实现（S17）：<b>能力批准 × 授权范围</b> 的交集。
 *
 * <p>{@code allows} 要同时成立两件：
 * <ol>
 *   <li>这个动作在 {@link Deployment#approvedActions()} 里（OP 逐条勾选过，§14.4）；</li>
 *   <li>{@link AuthorityData#isLicensed} —— 所有人档，或者这个 UUID 在指定名单里。</li>
 * </ol>
 *
 * <p>它被问两次：准入时（{@code ScriptPipeline.accept} 的部署判定之后由求值触发）与<b>落地前重查</b>
 * （{@code ScriptPipeline.land}，§15.9）。后者成立才真正执行意图 —— 请求排队期间 OP 撤权，
 * 效果就不会发生。
 */
public final class ServerAuthority implements AuthorityView {

    private final DeploymentData deployments;
    private final AuthorityData authority;

    public ServerAuthority(DeploymentData deployments, AuthorityData authority) {
        this.deployments = deployments;
        this.authority = authority;
    }

    @Override
    public boolean allows(UUID player, String appId, String actionId) {
        if (player == null || appId == null || actionId == null) return false;
        Deployment d = deployments.deployment(appId);
        if (d == null || !d.approves(actionId)) return false;   // 能力没批（或部署没了）
        return authority.isLicensed(appId, player);             // 范围没给到他
    }
}
