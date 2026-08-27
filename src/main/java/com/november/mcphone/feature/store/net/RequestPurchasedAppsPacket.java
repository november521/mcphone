package com.november.mcphone.feature.store.net;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 网络包：客户端 → 服务端，"我进商店了，告诉我买过哪些"。
 *
 * 购买记录存在服务端玩家附件里，客户端一无所知，所以每次打开商店都要问
 * 一次。不做登录时主动下发：那要挂一个登录事件，而玩家可能整局都不开商店，
 * 为一个多数时候用不上的集合占登录流程不值当。
 *
 * 包体没有字段：问的是"发包这名玩家买过什么"，服务端从连接上下文就能拿到
 * 玩家。带上玩家 ID 反而是给伪造客户端开后门——那等于允许查别人买过什么。
 */
public record RequestPurchasedAppsPacket() {

    public static void encode(RequestPurchasedAppsPacket msg, FriendlyByteBuf buf) {
    }

    public static RequestPurchasedAppsPacket decode(FriendlyByteBuf buf) {
        return new RequestPurchasedAppsPacket();
    }
}
