package com.november.mcphone.network.chat;

import com.november.mcphone.MCphone;
import com.november.mcphone.chat.ContactsData;
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
 * 列表上限取联系人上限的两倍：会话列表 = 联系人 ∪ 有消息往来的人，
 * 后者可能包含还没加为联系人的陌生人，留出余量。上限写在编解码器上，
 * 解码阶段就拒收超量数据，不必等到业务层才发现。
 */
public record SyncConversationsPacket(List<ConversationSummary> conversations)
        implements CustomPacketPayload {

    /** 单个包能携带的会话数上限 */
    public static final int MAX_CONVERSATIONS = ContactsData.MAX_CONTACTS * 2;

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
