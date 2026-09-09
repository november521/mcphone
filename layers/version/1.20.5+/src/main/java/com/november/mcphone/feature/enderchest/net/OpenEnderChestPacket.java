package com.november.mcphone.feature.enderchest.net;

import com.november.mcphone.MCphone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：玩家在手机里点了「末影箱」。
 * 包体故意没字段：服务端从连接上下文取玩家，带玩家 ID 等于给伪造客户端开后门。
 */
public record OpenEnderChestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenEnderChestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "open_ender_chest"));

    public static final StreamCodec<FriendlyByteBuf, OpenEnderChestPacket> STREAM_CODEC =
            StreamCodec.of((buf, msg) -> encode(msg, buf), OpenEnderChestPacket::decode);

    /** 没有字段，一个字节都不写。多写一个字节，对面就会把它当成下一个包的开头 */
    public static void encode(OpenEnderChestPacket msg, FriendlyByteBuf buf) {
    }

    public static OpenEnderChestPacket decode(FriendlyByteBuf buf) {
        return new OpenEnderChestPacket();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
