package com.november.mcphone.network.store;

import com.november.mcphone.MCphone;
import com.november.mcphone.cost.PurchasedApps;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：服务端 → 客户端，下发这名玩家买过的全部 App。
 *
 * 整份下发而不是增量：这份集合最多几十条 id，比任何一次增量协议都便宜，
 * 也不存在"客户端漏了一条就永远对不上"的问题。购买成功后同样整份重发。
 *
 * 条数上限在 {@link PurchasedApps#STREAM_CODEC} 层面封死。
 */
public record SyncPurchasedAppsPacket(PurchasedApps purchased) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncPurchasedAppsPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "sync_purchased_apps"));

    public static final StreamCodec<ByteBuf, SyncPurchasedAppsPacket> STREAM_CODEC =
            PurchasedApps.STREAM_CODEC.map(SyncPurchasedAppsPacket::new,
                    SyncPurchasedAppsPacket::purchased);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
