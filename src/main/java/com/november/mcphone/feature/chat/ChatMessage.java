package com.november.mcphone.feature.chat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

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
    public static final StreamCodec<ByteBuf, ChatMessage> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ChatMessage::sender,
                    ByteBufCodecs.stringUtf8(MAX_TEXT_LENGTH), ChatMessage::text,
                    ByteBufCodecs.VAR_LONG, ChatMessage::time,
                    ChatMessage::new
            );
}
