package com.november.mcphone.feature.enderchest.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
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

    public static final StreamCodec<ByteBuf, OpenEnderChestPacket> STREAM_CODEC =
            StreamCodec.unit(new OpenEnderChestPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
