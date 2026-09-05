package com.november.mcphone.feature.chat;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;
import java.util.function.Function;

/**
 * 一条聊天消息。sender 存 UUID 而不是名字（玩家可以改名）；time 是 currentTimeMillis，毫秒；
 * 正文是什么由 {@link MessageBody} 说了算——文本、图片，日后还可能有别的。
 */
public record ChatMessage(UUID sender, long time, MessageBody body) {

    /** 1.8.19 及更早的存档格式：{sender, text, time}，正文只能是文本 */
    private static final Codec<ChatMessage> LEGACY_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUIDUtil.CODEC.fieldOf("sender").forGetter(ChatMessage::sender),
                    Codec.STRING.fieldOf("text").forGetter(ChatMessage::legacyText),
                    Codec.LONG.fieldOf("time").forGetter(ChatMessage::time)
            ).apply(instance, (sender, text, time) -> new ChatMessage(sender, time, new TextBody(text)))
    );

    /** 现行格式：正文单独一层，里面第一个字段是 kind */
    private static final Codec<ChatMessage> MODERN_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUIDUtil.CODEC.fieldOf("sender").forGetter(ChatMessage::sender),
                    Codec.LONG.fieldOf("time").forGetter(ChatMessage::time),
                    MessageBody.CODEC.fieldOf("body").forGetter(ChatMessage::body)
            ).apply(instance, ChatMessage::new)
    );

    /**
     * 两种格式都读得懂；写的时候，【文本消息仍按老格式写】。
     *
     * 读：先试新格式（认 body 字段），不成再试老格式（认 text 字段），两者字段不重叠，
     * 不会互相误判。
     *
     * 写：文本消息照老样子写，是为了让服主能把模组降回去。整份聊天记录是一个 Codec
     * 解出来的，中间只要有一条读不懂，【整个 map 都解不出来】——那不是"少一条消息"，
     * 是所有人的聊天记录一起消失。一台从没人发过图的服务器，降级后存档里一个新字段
     * 都不会有；发过图的，也只是那几条图片消息读不出来。
     */
    public static final Codec<ChatMessage> CODEC =
            Codec.either(MODERN_CODEC, LEGACY_CODEC).xmap(
                    either -> either.map(Function.identity(), Function.identity()),
                    message -> message.body() instanceof TextBody
                            ? Either.right(message)
                            : Either.left(message)
            );

    public static final StreamCodec<ByteBuf, ChatMessage> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ChatMessage::sender,
                    ByteBufCodecs.VAR_LONG, ChatMessage::time,
                    MessageBody.STREAM_CODEC, ChatMessage::body,
                    ChatMessage::new
            );

    /** 便利构造：一条文本消息 */
    public static ChatMessage text(UUID sender, String text, long time) {
        return new ChatMessage(sender, time, new TextBody(text));
    }

    /**
     * 老格式只写得下文本。
     *
     * 只有 {@link #CODEC} 在正文确实是 TextBody 时才会走到这条路上，
     * 别处调等于问"一张图片的文本是什么"，那是个提问本身就错了的问题。
     */
    private String legacyText() {
        if (body instanceof TextBody t) return t.text();
        throw new IllegalStateException("非文本消息不该按老格式写: " + body.kind());
    }
}
