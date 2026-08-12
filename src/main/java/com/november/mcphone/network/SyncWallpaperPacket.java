package com.november.mcphone.network;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：服务端 → 客户端，同步当前玩家选择的壁纸文件名。
 *
 * 流程：
 * 1. 玩家在手机上选择壁纸
 * 2. 客户端发 C2S 包给服务端 → 服务端存入 Attachment
 * 3. 服务端发此包给客户端 → 客户端更新本地缓存的壁纸
 */
public record SyncWallpaperPacket(String wallpaperFileName) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncWallpaperPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "sync_wallpaper"));

    public static final StreamCodec<ByteBuf, SyncWallpaperPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    SyncWallpaperPacket::wallpaperFileName,
                    SyncWallpaperPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
