package com.november.mcphone.network.store;

import com.november.mcphone.cost.PurchasedApps;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端这一侧记着"我买过哪些 App"。
 *
 * 权威副本在服务端附件里，这里只是一份用来画界面的镜像：详情页要据此决定
 * 按钮是"购买"还是"下载"。改这里不会让任何人白拿 App——真正的判断在
 * {@link StoreNetworking} 的服务端分支。
 *
 * ============================================================
 * 为什么这个类里一个客户端类型都没有
 * ============================================================
 *
 * 它被 StoreNetworking 引用，而那个类两端都会加载（网络包的注册必须两端
 * 对称）。这里但凡出现 net.minecraft.client.*，专用服务器就会在注册网络包
 * 时崩。ChatClientCache 与 NotesClientCache 守的是同一条线。
 *
 * 所以它只是个数据盒子，不碰任何界面。
 */
public final class StoreClientCache {

    private StoreClientCache() {}

    private static PurchasedApps purchased = PurchasedApps.EMPTY;

    /** 有没有收到过服务端的答复。没收到时界面显示"加载中"而不是"未购买" */
    private static boolean synced = false;

    /**
     * 同步到达时通知谁。
     *
     * 用监听器而不是让本类直接调 PhoneScreenRegistry：本类被两端都加载的
     * StoreNetworking 引用，而那个注册表现在含客户端类型（Minecraft、
     * ServerData）。直接引用会让专用服务器在注册网络包时崩。
     * ChatClientCache 通知 ChatNotifier 用的是同一个办法。
     */
    private static Runnable syncListener = null;

    /** 由 MCphoneClient 挂上 */
    public static void setSyncListener(Runnable listener) {
        syncListener = listener;
    }

    /** 由 S2C 同步包调用 */
    static void set(PurchasedApps value) {
        purchased = value == null ? PurchasedApps.EMPTY : value;
        synced = true;
        if (syncListener != null) syncListener.run();
    }

    public static boolean isSynced() {
        return synced;
    }

    public static boolean has(ResourceLocation appId) {
        return purchased.has(appId);
    }

    /** 向服务端要一份最新的。进商店时调 */
    public static void request() {
        PacketDistributor.sendToServer(new RequestPurchasedAppsPacket());
    }

    /** 发起一次购买。结果会以同步包的形式回来 */
    public static void purchase(ResourceLocation appId) {
        if (appId == null) return;
        PacketDistributor.sendToServer(new PurchaseAppPacket(appId));
    }

    /**
     * 退出世界时清空。
     *
     * 不清的话，换到另一个服务器会先闪出上一个服务器的购买记录——那是别处
     * 的数据，玩家会以为自己已经买过，点了"下载"却发现装不上。聊天与记事本
     * 的缓存在同一处清理。
     */
    public static void clear() {
        purchased = PurchasedApps.EMPTY;
        synced = false;
    }
}
