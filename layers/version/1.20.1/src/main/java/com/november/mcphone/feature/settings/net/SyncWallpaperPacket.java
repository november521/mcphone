package com.november.mcphone.feature.settings.net;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 网络包：服务端 → 客户端，同步当前玩家选择的壁纸文件名。
 *
 * 流程：
 * 1. 玩家在手机上选择壁纸
 * 2. 客户端发 C2S 包给服务端 → 服务端存入 Capability
 * 3. 服务端发此包给客户端 → 客户端更新本地缓存的壁纸
 *
 * 第 2 步与 NeoForge 那一支不同：那边存的是 Attachment，这边是 Capability，
 * 两套模型的差别见 docs/PORTING.md 第 3 条。对客户端来说没有区别。
 */
public record SyncWallpaperPacket(String wallpaperFileName) {

    public static void encode(SyncWallpaperPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.wallpaperFileName());
    }

    public static SyncWallpaperPacket decode(FriendlyByteBuf buf) {
        return new SyncWallpaperPacket(buf.readUtf());
    }
}
