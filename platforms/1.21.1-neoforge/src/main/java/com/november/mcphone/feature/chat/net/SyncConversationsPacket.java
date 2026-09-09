package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.chat.FriendData;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** S2C：整个会话列表，只含摘要。上限取好友数上限并写在编解码器上，解码阶段就拒收超量数据。 */
public record SyncConversationsPacket(List<ConversationSummary> conversations)
        implements CustomPacketPayload {

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
