package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.chat.ChatDeletionResult;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

/** S2C：权威历史及摘要先同步，再确认操作；客户端不做乐观删除。 */
public record ChatMessageDeleteResultPacket(UUID peer, UUID messageId, UUID requestId, ChatDeletionResult result) implements CustomPacketPayload {
    public static final Type<ChatMessageDeleteResultPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "chat_message_delete_result"));
    public static final StreamCodec<ByteBuf, ChatMessageDeleteResultPacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, ChatMessageDeleteResultPacket::peer,
            UUIDUtil.STREAM_CODEC, ChatMessageDeleteResultPacket::messageId,
            UUIDUtil.STREAM_CODEC, ChatMessageDeleteResultPacket::requestId,
            ByteBufCodecs.VAR_INT.map(ChatMessageDeleteResultPacket::decodeResult, ChatDeletionResult::wireId), ChatMessageDeleteResultPacket::result,
            ChatMessageDeleteResultPacket::new);
    public static ChatDeletionResult decodeResult(int ordinal) {
        return ChatDeletionResult.fromWire(ordinal);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
