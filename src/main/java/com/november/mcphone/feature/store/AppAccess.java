package com.november.mcphone.feature.store;

import com.november.mcphone.core.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端这一侧的准入判断：这名玩家有没有资格用这个 App。
 *
 * ============================================================
 * 为什么"装上了"不等于"能用"
 * ============================================================
 *
 * 安装是纯客户端的动作——App 的实现随模组加载进来了，装进主屏只是改一个
 * 本地集合。改个客户端就能把任何 App 塞进主屏，购买那一步完全绕开。
 *
 * 所以凡是要服务端干活的 App（开末影箱、开传送石选点），服务端都得自己
 * 问一句"你买过吗"，而不是相信客户端敢发这个包就说明它有资格。
 *
 * ============================================================
 * 免费的一律放行
 * ============================================================
 *
 * 没被报过价的 App 压根不走购买流程，购买记录里也永远不会有它们。要求它们
 * "先购买"等于把免费 App 全部锁死。
 *
 * 这也意味着这道闸只对付费 App 生效——而付费 App 现在只有两个。将来附属
 * 模组给自己的 App 定了价，同一道闸自动适用，它们不需要知道这个类存在。
 */
public final class AppAccess {

    private AppAccess() {}

    /**
     * 这名玩家现在能用这个 App 吗。
     *
     * 创造模式不放行。创造模式玩家可以直接在商店里免费买下（ICost 在创造
     * 模式下一律返回付得起且不真扣），走一遍正常流程即可——为它开后门只会
     * 多出一条没人测过的分支。
     */
    public static boolean canUse(ServerPlayer player, ResourceLocation appId) {
        if (player == null || appId == null) return false;
        if (!AppPriceRegistry.isPaid(appId)) return true;
        return player.getData(ModAttachments.PURCHASED_APPS.get()).has(appId);
    }
}
