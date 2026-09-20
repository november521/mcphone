package com.november.mcphone.core.script.server;

/**
 * S15g 的占位部署视图：<b>本服还没有任何已批准的部署</b>，对任何输入都拒绝。
 *
 * <p>S17 用真正的部署表（谁批准了哪个 App 的哪个动作）替换它。在那之前，每一个请求都回
 * {@code NOT_DEPLOYED} —— 那正是"本服没有这个 App 的已批准部署"的字面意思，不是兜底。
 *
 * <p><b>不许</b>在这里写任何"允许"分支（哪怕只是"开发环境放行"）：授权判定不许为跑清单而放宽（E34⑤）。
 */
public final class DenyAllDeployments implements DeploymentView {

    @Override
    public boolean deployed(String appId) {
        return false;
    }

    @Override
    public boolean hasAction(String appId, String actionId) {
        return false;
    }

    @Override
    public String deployRev(String appId) {
        return null;
    }
}
