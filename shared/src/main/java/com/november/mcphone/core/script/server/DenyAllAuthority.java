package com.november.mcphone.core.script.server;

import java.util.UUID;

/**
 * S15g 的占位授权视图：<b>对任何玩家、任何动作都拒绝</b>。
 *
 * <p>S17 用真正的授权表替换它。在此之前，落地那一层重查授权一律不过，回 {@code NOT_AUTHORIZED} ——
 * 也就是"动作没有被批准"，不是兜底。
 *
 * <p><b>不许</b>在这里写任何"允许"分支（E34⑤：授权判定不许为跑清单而放宽）。
 */
public final class DenyAllAuthority implements AuthorityView {

    @Override
    public boolean allows(UUID player, String appId, String actionId) {
        return false;
    }
}
