package com.november.mcphone.feature.music.net;

import net.minecraft.network.FriendlyByteBuf;

/**
 * C2S：玩家要开唱片仓那个带背包的界面。容器菜单必须由服务端 openMenu 建立。
 * 故意没有字段：开的是发包玩家自己的唱片仓，带玩家 ID 反而给伪造客户端开后门。
 */
public record OpenDiscBayPacket() {

    public static void encode(OpenDiscBayPacket msg, FriendlyByteBuf buf) {
    }

    public static OpenDiscBayPacket decode(FriendlyByteBuf buf) {
        return new OpenDiscBayPacket();
    }
}
