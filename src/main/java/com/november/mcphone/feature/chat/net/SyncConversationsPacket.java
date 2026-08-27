package com.november.mcphone.feature.chat.net;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import com.november.mcphone.feature.chat.FriendData;

import java.util.List;

/** S2C：整个会话列表，只含摘要。上限取好友数上限并写在编解码器上，解码阶段就拒收超量数据。 */
public record SyncConversationsPacket(List<ConversationSummary> conversations) {

    public static final int MAX_CONVERSATIONS = FriendData.MAX_FRIENDS;

    public static void encode(SyncConversationsPacket msg, FriendlyByteBuf buf) {
        buf.writeCollection(msg.conversations(), (b, v) -> ConversationSummary.encode(v, b));
    }

    public static SyncConversationsPacket decode(FriendlyByteBuf buf) {
        return new SyncConversationsPacket(
                buf.readCollection(n -> {
            if (n > MAX_CONVERSATIONS) throw new DecoderException("列表超过上限 MAX_CONVERSATIONS: " + n);
            return new java.util.ArrayList<>(n);
        }, b -> ConversationSummary.decode(buf)));
    }
}
