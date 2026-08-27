package com.november.mcphone.feature.enderchest.net;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端：玩家在手机里点了「末影箱」。
 * 包体故意没字段：服务端从连接上下文取玩家，带玩家 ID 等于给伪造客户端开后门。
 */
public record OpenEnderChestPacket() {

    public static void encode(OpenEnderChestPacket msg, FriendlyByteBuf buf) {
    }

    public static OpenEnderChestPacket decode(FriendlyByteBuf buf) {
        return new OpenEnderChestPacket();
    }
}
