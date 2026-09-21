package com.november.mcphone.core.script.server;

/**
 * {@link DeploymentView} 的生产实现：只读 {@link DeploymentData}（S17）。
 *
 * <p>两轴的分工是故意的（§13.6 第 4/5 行）：
 * <ul>
 *   <li>{@link #deployed}/{@link #hasAction} 查 <b>declaredActions</b> —— 动作压根没在包里声明，
 *       就当部署不存在（{@code NOT_DEPLOYED}，连限流桶都不建）；</li>
 *   <li>声明了但 OP 没批的动作，这里<b>放行</b>（它确实是这个部署的动作），由 {@link ServerAuthority}
 *       在授权层拒（{@code NOT_AUTHORIZED}）—— 两个失败码对应两件不同的事，别合并。</li>
 * </ul>
 *
 * <p>只在服务端主线程调用（{@code ScriptPipeline.accept} 的契约）。
 */
public final class ServerDeployments implements DeploymentView {

    private final DeploymentData data;

    public ServerDeployments(DeploymentData data) {
        this.data = data;
    }

    @Override
    public boolean deployed(String appId) {
        return appId != null && data.deployment(appId) != null;
    }

    @Override
    public boolean hasAction(String appId, String actionId) {
        Deployment d = appId == null ? null : data.deployment(appId);
        return d != null && actionId != null && d.declaredActions().contains(actionId);
    }

    @Override
    public String deployRev(String appId) {
        Deployment d = appId == null ? null : data.deployment(appId);
        return d == null ? null : d.revision();
    }

    @Override
    public java.util.Set<String> approvedCapabilities(String appId) {
        Deployment d = appId == null ? null : data.deployment(appId);
        return d == null ? java.util.Set.of() : new java.util.LinkedHashSet<>(d.approvedCapabilities());
    }
}
