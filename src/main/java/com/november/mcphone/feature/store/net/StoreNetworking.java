package com.november.mcphone.feature.store.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.cost.ICost;
import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.core.PhoneItem;
import com.november.mcphone.feature.store.AppPriceRegistry;
import com.november.mcphone.feature.store.PurchasedApps;
import com.november.mcphone.core.net.RequestThrottle;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * 应用商店购买流程的网络层（服务端一半）。
 *
 * 一条规矩：客户端说什么都不算数
 *
 * 价格由服务端自己查，购买记录存在服务端附件里，扣物品在服务端做。客户端
 * 只负责"我想买这个"和把结果画出来。
 *
 * S2C 客户端接收在 {@code feature/store/client/StoreNetworkingClient}。
 */
public final class StoreNetworking {

    private StoreNetworking() {}

    /** 由 NetworkHandler.registerServer 调用 */
    public static void registerServer() {
        PayloadTypeRegistry.playC2S().register(RequestPurchasedAppsPacket.TYPE, RequestPurchasedAppsPacket.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RequestPurchasedAppsPacket.TYPE, StoreNetworking::handleRequest);

        PayloadTypeRegistry.playC2S().register(PurchaseAppPacket.TYPE, PurchaseAppPacket.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PurchaseAppPacket.TYPE, StoreNetworking::handlePurchase);
    }

    //  服务端侧

    private static void handleRequest(RequestPurchasedAppsPacket packet, ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();
        if (!RequestThrottle.allow(player, RequestThrottle.Kind.PURCHASED)) return;
        sync(player);
    }

    /**
     * 买一个 App。
     *
     * 检查顺序是有讲究的：先排除"根本不该走到这一步"的情况（没手机、不是
     * 付费 App、已经买过），最后才碰玩家的物品。
     */
    private static void handlePurchase(PurchaseAppPacket packet, ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();

        // 与末影箱、传送石一致：身上得真有手机
        if (!PhoneItem.isCarriedBy(player)) {
            MCphone.LOGGER.debug("玩家 {} 请求购买但身上没有手机，已忽略",
                    player.getName().getString());
            return;
        }

        ResourceLocation appId = packet.appId();

        // 白名单：只有被报过价的才能买
        if (!AppPriceRegistry.isPaid(appId)) {
            MCphone.LOGGER.debug("玩家 {} 请求购买未定价的 '{}'，已忽略",
                    player.getName().getString(), appId);
            return;
        }

        PurchasedApps owned = player.getAttachedOrCreate(ModAttachments.PURCHASED_APPS);

        // 已经买过就直接回一份同步，不重复扣
        if (owned.has(appId)) {
            sync(player);
            return;
        }

        if (owned.isFull()) {
            fail(player, "mcphone.store.error.too_many");
            return;
        }

        ICost cost = AppPriceRegistry.priceOf(appId);

        // 先问够不够，再扣
        if (!cost.canAfford(player)) {
            player.displayClientMessage(
                    Component.translatable("mcphone.store.error.cannot_afford", cost.describe())
                            .withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!cost.consume(player)) {
            fail(player, "mcphone.store.error.purchase_failed");
            return;
        }

        player.setAttached(ModAttachments.PURCHASED_APPS, owned.with(appId));
        sync(player);

        MCphone.LOGGER.info("玩家 {} 购买了 App {}，代价 {}",
                player.getName().getString(), appId, cost.describe().getString());
    }

    /** 把这名玩家买过的全部回发给他 */
    private static void sync(ServerPlayer player) {
        ServerPlayNetworking.send(player, new SyncPurchasedAppsPacket(
                player.getAttachedOrCreate(ModAttachments.PURCHASED_APPS)));
    }

    private static void fail(ServerPlayer player, String key) {
        player.displayClientMessage(
                Component.translatable(key).withStyle(ChatFormatting.RED), true);
    }
}
