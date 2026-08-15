package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.chat.ChatData;
import com.november.mcphone.feature.chat.ChatMessage;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * 网络包：服务端 → 客户端，某个会话的历史消息。
 *
 * 条数上限直接取存储侧的上限：服务端每对会话最多就保留那么多条，
 * 传输上限比它大没有意义，比它小则会截断真实数据。两处用同一个常量，
 * 日后改存储上限不会忘了改这里。
 */
public record SyncMessagesPacket(UUID peer, List<ChatMessage> messages)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncMessagesPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "sync_messages"));

    public static final StreamCodec<ByteBuf, SyncMessagesPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, SyncMessagesPacket::peer,
                    ChatMessage.STREAM_CODEC.apply(
                            ByteBufCodecs.list(ChatData.MAX_MESSAGES_PER_CONVERSATION)),
                    SyncMessagesPacket::messages,
                    SyncMessagesPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
