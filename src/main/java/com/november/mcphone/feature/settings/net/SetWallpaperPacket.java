package com.november.mcphone.feature.settings.net;

import com.mojang.logging.LogUtils;
import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/**
 * 网络包：客户端 → 服务端，玩家在手机上选择了一张壁纸。
 */
public record SetWallpaperPacket(String wallpaperFileName) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final CustomPacketPayload.Type<SetWallpaperPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "set_wallpaper"));

    public static final StreamCodec<ByteBuf, SetWallpaperPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    SetWallpaperPacket::wallpaperFileName,
                    SetWallpaperPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
