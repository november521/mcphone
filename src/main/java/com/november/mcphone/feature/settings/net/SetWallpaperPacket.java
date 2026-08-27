package com.november.mcphone.feature.settings.net;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 网络包：客户端 → 服务端，玩家在手机上选择了一张壁纸。
 *
 * 与 NeoForge 那一支的形状差别
 *
 * 那边这个 record 实现 CustomPacketPayload，自带一个 ResourceLocation 类型
 * 和一个 StreamCodec。1.20.1 上两样都没有：包只是个普通 record，编解码是
 * 一对静态方法，身份由注册时的整数序号决定（见 MCphoneNetwork）。
 *
 * writeUtf/readUtf 不带参数时上限是 32767 字符，与那边
 * ByteBufCodecs.STRING_UTF8 的上限一致，所以两支能接住的东西一样多。
 * 这个上限【必须有】：读的是客户端送来的字节，不设限等于让任何人往
 * 服务端玩家存档里塞任意长的字符串。
 */
public record SetWallpaperPacket(String wallpaperFileName) {

    public static void encode(SetWallpaperPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.wallpaperFileName());
    }

    public static SetWallpaperPacket decode(FriendlyByteBuf buf) {
        return new SetWallpaperPacket(buf.readUtf());
    }
}
