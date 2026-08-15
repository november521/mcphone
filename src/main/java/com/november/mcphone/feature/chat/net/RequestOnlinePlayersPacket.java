package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：客户端 → 服务端，请求在线玩家列表。
 *
 * 打开"加联系人"界面时发一次。包体无字段——要的是"谁在线"，
 * 与请求方是谁无关，服务端从连接上下文取本人以便排除自己。
 */
public record RequestOnlinePlayersPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestOnlinePlayersPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "request_online_players"));

    public static final StreamCodec<ByteBuf, RequestOnlinePlayersPacket> STREAM_CODEC =
            StreamCodec.unit(new RequestOnlinePlayersPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
