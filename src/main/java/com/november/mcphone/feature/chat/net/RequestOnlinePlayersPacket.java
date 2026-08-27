package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S：打开"加联系人"界面时请求在线玩家列表，无字段；本人由服务端从连接上下文取。 */
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
