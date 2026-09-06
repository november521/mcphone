package com.november.mcphone.feature.store.client;

import com.november.mcphone.feature.store.net.SyncPurchasedAppsPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * 应用商店网络包（客户端一半）：S2C 注册 + 客户端接收。
 * 必须放在含 {@code /client/} 的包里。
 */
public final class StoreNetworkingClient {

    private StoreNetworkingClient() {}

    /** 由 core.client.ClientNetworking.register 调用 */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(SyncPurchasedAppsPacket.TYPE, SyncPurchasedAppsPacket.STREAM_CODEC);
        ClientPlayNetworking.registerGlobalReceiver(SyncPurchasedAppsPacket.TYPE, StoreNetworkingClient::handleSync);
    }

    private static void handleSync(SyncPurchasedAppsPacket packet, ClientPlayNetworking.Context ctx) {
        StoreClientCache.set(packet.purchased());
    }
}
