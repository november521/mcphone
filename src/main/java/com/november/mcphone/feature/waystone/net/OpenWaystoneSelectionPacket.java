package com.november.mcphone.feature.waystone.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：玩家在手机里点了「传送石」。
 * 包体故意没字段：服务端从连接上下文取玩家，带玩家 ID 等于允许伪造客户端点开别人的传送点。
 */
public record OpenWaystoneSelectionPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenWaystoneSelectionPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "open_waystone_selection"));

    public static final StreamCodec<ByteBuf, OpenWaystoneSelectionPacket> STREAM_CODEC =
            StreamCodec.unit(new OpenWaystoneSelectionPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
