package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

/** C2S：个人删除请求。所有者由连接玩家确定，票据只用于客户端确认归属。 */
public record DeleteChatMessagePacket(UUID peer, UUID messageId, UUID requestId) implements CustomPacketPayload {
    public static final Type<DeleteChatMessagePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "delete_chat_message"));
    public static final StreamCodec<ByteBuf, DeleteChatMessagePacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, DeleteChatMessagePacket::peer,
            UUIDUtil.STREAM_CODEC, DeleteChatMessagePacket::messageId,
            UUIDUtil.STREAM_CODEC, DeleteChatMessagePacket::requestId, DeleteChatMessagePacket::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
