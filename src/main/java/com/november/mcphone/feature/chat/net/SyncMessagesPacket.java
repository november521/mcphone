package com.november.mcphone.feature.chat.net;

import com.november.mcphone.feature.chat.ChatData;
import com.november.mcphone.feature.chat.ChatMessage;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;
import java.util.UUID;

/** S2C：某个会话的历史消息。条数上限与存储侧共用同一个常量，改存储上限不会忘了改这里。 */
public record SyncMessagesPacket(UUID peer, List<ChatMessage> messages) {

    public static void encode(SyncMessagesPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.peer());
        buf.writeCollection(msg.messages(), (b, v) -> ChatMessage.encode(v, b));
    }

    /**
     * 条数上限在解码这一侧封死。
     *
     * 1.21.1 那边写成 ByteBufCodecs.list(MAX)，上限由组合子带着；1.20.1 的
     * FriendlyByteBuf.readCollection 【没有上限参数】，得自己在分配前拦一道。
     * 照搬 readList 会让伪造的服务端把客户端的内存撑爆。
     */
    public static SyncMessagesPacket decode(FriendlyByteBuf buf) {
        UUID peer = buf.readUUID();
        List<ChatMessage> messages = buf.readCollection(n -> {
            if (n > ChatData.MAX_MESSAGES_PER_CONVERSATION) {
                throw new DecoderException("消息条数超过上限 "
                        + ChatData.MAX_MESSAGES_PER_CONVERSATION + ": " + n);
            }
            return new java.util.ArrayList<ChatMessage>(n);
        }, ChatMessage::decode);
        return new SyncMessagesPacket(peer, messages);
    }

}
