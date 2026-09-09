package com.november.mcphone.feature.chat.net;

import com.november.mcphone.core.net.Wire;
import com.november.mcphone.feature.chat.ChatData;
import com.november.mcphone.feature.chat.ChatMessage;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;
import java.util.UUID;

/** S2C：某个会话的历史消息。条数上限与存储侧共用同一个常量，改存储上限不会忘了改这里。 */
public record SyncMessagesPacket(UUID peer, List<ChatMessage> messages) {

    public static void encode(SyncMessagesPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.peer());
        Wire.writeList(buf, msg.messages(), ChatData.MAX_MESSAGES_PER_CONVERSATION, ChatMessage::encode);
    }

    /**
     * 条数上限两侧都封死，见 {@link Wire}：1.21.1 那边由 ByteBufCodecs.list(MAX)
     * 一并带着，这边得显式交给 Wire。
     */
    public static SyncMessagesPacket decode(FriendlyByteBuf buf) {
        UUID peer = buf.readUUID();
        List<ChatMessage> messages =
                Wire.readList(buf, ChatData.MAX_MESSAGES_PER_CONVERSATION, ChatMessage::decode);
        return new SyncMessagesPacket(peer, messages);
    }

}
