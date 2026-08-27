package com.november.mcphone.feature.store.net;

import com.november.mcphone.feature.store.PurchasedApps;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 网络包：服务端 → 客户端，下发这名玩家买过的全部 App。
 *
 * 整份下发而不是增量：这份集合最多几十条 id，比任何一次增量协议都便宜，
 * 也不存在"客户端漏了一条就永远对不上"的问题。购买成功后同样整份重发。
 *
 * 条数上限在 {@link PurchasedApps#decode} 层面封死。
 */
public record SyncPurchasedAppsPacket(PurchasedApps purchased) {

    public static void encode(SyncPurchasedAppsPacket msg, FriendlyByteBuf buf) {
        PurchasedApps.encode(msg.purchased(), buf);
    }

    public static SyncPurchasedAppsPacket decode(FriendlyByteBuf buf) {
        return new SyncPurchasedAppsPacket(PurchasedApps.decode(buf));
    }

}
