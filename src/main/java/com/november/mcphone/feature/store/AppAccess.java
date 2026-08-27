package com.november.mcphone.feature.store;

import com.november.mcphone.core.ModCapabilities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端侧的准入判断。"装上了"是纯客户端动作、不可信，凡要服务端干活的
 * App 都得在这里问一句"买过吗"；免费（没报价）的一律放行。
 */
public final class AppAccess {

    private AppAccess() {}

    /** 创造模式也不开后门：创造下 ICost 本就付得起且不真扣，照走购买流程 */
    public static boolean canUse(ServerPlayer player, ResourceLocation appId) {
        if (player == null || appId == null) return false;
        if (!AppPriceRegistry.isPaid(appId)) return true;
        return ModCapabilities.of(player).purchasedApps().has(appId);
    }
}
