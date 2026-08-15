package com.november.mcphone.feature.store.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.core.PhoneItem;
import com.november.mcphone.api.cost.ICost;
import com.november.mcphone.feature.store.AppPriceRegistry;
import com.november.mcphone.feature.store.PurchasedApps;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.net.NetworkHandler;

/**
 * 应用商店购买流程的网络层。
 *
 * ============================================================
 * 一条规矩：客户端说什么都不算数
 * ============================================================
 *
 * 价格由服务端自己查，购买记录存在服务端附件里，扣物品在服务端做。客户端
 * 只负责"我想买这个"和把结果画出来。这不是谨慎，是必须——价格要扣真东西，
 * 而客户端整个都在玩家手里。
 *
 * ============================================================
 * 为什么只有【被报过价】的 App 才能购买
 * ============================================================
 *
 * 因为服务端不知道世界上有哪些 App。App 目录（PhoneScreenRegistry）是
 * 客户端专用的——IPhoneApp 的签名里有 GuiGraphics，服务端加载不了。
 *
 * 于是"这个 id 是不是一个真的 App"服务端答不上来。不管的话，伪造客户端
 * 可以拿任意字符串来"购买"，反正没报价就是免费，附件会被垃圾 id 撑爆。
 *
 * 改用价格表当白名单就解决了：能购买的 id 只可能来自价格表，而价格表是
 * 我们自己扫出来的。免费 App 本来也不需要购买这一步——详情页直接给"下载"。
 */
public final class StoreNetworking {

    private StoreNetworking() {}

    /** 由 NetworkHandler.register 调用 */
    public static void register(PayloadRegistrar registrar) {
        // C2S: 进商店了，问我买过哪些
        registrar.playToServer(
                RequestPurchasedAppsPacket.TYPE,
                RequestPurchasedAppsPacket.STREAM_CODEC,
                StoreNetworking::handleRequest
        );

        // C2S: 买这个
        registrar.playToServer(
                PurchaseAppPacket.TYPE,
                PurchaseAppPacket.STREAM_CODEC,
                StoreNetworking::handlePurchase
        );

        // S2C: 下发买过的全部
        registrar.playToClient(
                SyncPurchasedAppsPacket.TYPE,
                SyncPurchasedAppsPacket.STREAM_CODEC,
                StoreNetworking::handleSync
        );
    }

    // ============================================================
    //  服务端侧
    // ============================================================

    private static void handleRequest(RequestPurchasedAppsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            sync(player, ctx);
        });
    }

    /**
     * 买一个 App。
     *
     * 检查顺序是有讲究的：先排除"根本不该走到这一步"的情况（没手机、不是
     * 付费 App、已经买过），最后才碰玩家的物品。反过来的话，一次被拒绝的
     * 购买也可能已经扣掉了东西。
     */
    private static void handlePurchase(PurchaseAppPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            // 与末影箱、传送石一致：身上得真有手机。没有这道检查，改个客户端
            // 就能不掏手机买东西，手机这个前提条件形同虚设
            if (!PhoneItem.isCarriedBy(player)) {
                MCphone.LOGGER.debug("玩家 {} 请求购买但身上没有手机，已忽略",
                        player.getName().getString());
                return;
            }

            ResourceLocation appId = packet.appId();

            // 白名单：只有被报过价的才能买。理由见类注释
            if (!AppPriceRegistry.isPaid(appId)) {
                MCphone.LOGGER.debug("玩家 {} 请求购买未定价的 '{}'，已忽略",
                        player.getName().getString(), appId);
                return;
            }

            PurchasedApps owned = player.getData(ModAttachments.PURCHASED_APPS.get());

            // 已经买过就直接回一份同步，不重复扣。走到这里通常是客户端的
            // 缓存过期了（比如换了台设备登录），不是攻击，所以照常回话
            if (owned.has(appId)) {
                sync(player, ctx);
                return;
            }

            if (owned.isFull()) {
                fail(player, "mcphone.store.error.too_many");
                return;
            }

            ICost cost = AppPriceRegistry.priceOf(appId);

            // 先问够不够，再扣。ICost 的实现自己也会再判一次——两道都留着，
            // 因为这里要的是"没扣成时给玩家一句人话"，而 consume 只回 false
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

            player.setData(ModAttachments.PURCHASED_APPS.get(), owned.with(appId));
            sync(player, ctx);

            MCphone.LOGGER.info("玩家 {} 购买了 App {}，代价 {}",
                    player.getName().getString(), appId, cost.describe().getString());
        });
    }

    /** 把这名玩家买过的全部回发给他 */
    private static void sync(ServerPlayer player, IPayloadContext ctx) {
        ctx.reply(new SyncPurchasedAppsPacket(
                player.getData(ModAttachments.PURCHASED_APPS.get())));
    }

    private static void fail(ServerPlayer player, String key) {
        player.displayClientMessage(
                Component.translatable(key).withStyle(ChatFormatting.RED), true);
    }

    // ============================================================
    //  客户端侧
    // ============================================================

    private static void handleSync(SyncPurchasedAppsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> StoreClientCache.set(packet.purchased()));
    }
}
