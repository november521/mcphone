package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.chat.FriendData;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 网络包：服务端 → 客户端，整个会话列表。
 *
 * 只含摘要，不含历史消息（见 {@link ConversationSummary} 的说明）。
 *
 * 上限直接取好友数上限：会话列表就是好友列表，非好友之间不能聊天，
 * 不会有第三方混进来。上限写在编解码器上，解码阶段就拒收超量数据，
 * 不必等到业务层才发现。
 */
public record SyncConversationsPacket(List<ConversationSummary> conversations)
        implements CustomPacketPayload {

    /** 单个包能携带的会话数上限 */
    public static final int MAX_CONVERSATIONS = FriendData.MAX_FRIENDS;

    public static final CustomPacketPayload.Type<SyncConversationsPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "sync_conversations"));

    public static final StreamCodec<ByteBuf, SyncConversationsPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ConversationSummary.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CONVERSATIONS)),
                    SyncConversationsPacket::conversations,
                    SyncConversationsPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
