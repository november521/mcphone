package com.november.mcphone.feature.settings.net;

import com.mojang.logging.LogUtils;
import com.november.mcphone.MCphone;
import net.minecraft.network.FriendlyByteBuf;
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

    public static final StreamCodec<FriendlyByteBuf, SetWallpaperPacket> STREAM_CODEC =
            StreamCodec.of((buf, msg) -> encode(msg, buf), SetWallpaperPacket::decode);

    public static void encode(SetWallpaperPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.wallpaperFileName());
    }

    public static SetWallpaperPacket decode(FriendlyByteBuf buf) {
        return new SetWallpaperPacket(buf.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
