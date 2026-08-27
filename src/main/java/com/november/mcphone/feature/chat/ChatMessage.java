package com.november.mcphone.feature.chat;

import net.minecraft.network.FriendlyByteBuf;
import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.UUID;

/** 一条聊天消息。sender 存 UUID 而不是名字（玩家可以改名）；text 存原样，清洗在收包时做；time 是 currentTimeMillis，毫秒。 */
public record ChatMessage(UUID sender, String text, long time) {

    /** 单条消息长度上限，在编解码器层面封死 */
    public static final int MAX_TEXT_LENGTH = 256;

    public static final Codec<ChatMessage> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUIDUtil.CODEC.fieldOf("sender").forGetter(ChatMessage::sender),
                    Codec.STRING.fieldOf("text").forGetter(ChatMessage::text),
                    Codec.LONG.fieldOf("time").forGetter(ChatMessage::time)
            ).apply(instance, ChatMessage::new)
    );

    /** 超长文本在解码阶段就被拒收，轮不到业务层 */
    public static void encode(ChatMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.sender());
        buf.writeUtf(msg.text(), MAX_TEXT_LENGTH);
        buf.writeVarLong(msg.time());
    }

    public static ChatMessage decode(FriendlyByteBuf buf) {
        return new ChatMessage(
                buf.readUUID(),
                buf.readUtf(MAX_TEXT_LENGTH),
                buf.readVarLong());
    }
}
