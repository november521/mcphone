package com.november.mcphone.network.chat;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：客户端 → 服务端，请求刷新会话列表。
 *
 * 打开聊天 App 时发一次。包体无字段——要的是发包这名玩家自己的会话，
 * 服务端从连接上下文就拿得到玩家；带上玩家 ID 反而是给伪造客户端
 * 开后门，让人能窥探别人的会话列表。
 */
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
