package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S：打开聊天 App 时请求会话列表。故意无字段：玩家取自连接上下文，带玩家 ID 等于给伪造客户端开窥探别人会话的后门。 */
public record RequestConversationsPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestConversationsPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "request_conversations"));

    public static final StreamCodec<ByteBuf, RequestConversationsPacket> STREAM_CODEC =
            StreamCodec.unit(new RequestConversationsPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
