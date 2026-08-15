package com.november.mcphone.feature.chat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/**
 * 一条聊天消息。
 *
 * @param sender 发送者 UUID。存 UUID 而不是玩家名：玩家可以改名，
 *               存名字的话历史记录会指向一个不存在的人。显示名在
 *               渲染时按 UUID 现查。
 * @param text   正文。存原样，清洗在收包时做——存进来的已经是干净的。
 * @param time   发送时刻，System.currentTimeMillis()。用于排序、显示
 *               时间戳，以及与"上次已读时刻"比较算未读数。
 */
public record ChatMessage(UUID sender, String text, long time) {

    /** 单条消息长度上限，编解码器层面封死，防止伪造客户端塞超长文本 */
    public static final int MAX_TEXT_LENGTH = 256;

    public static final Codec<ChatMessage> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUIDUtil.CODEC.fieldOf("sender").forGetter(ChatMessage::sender),
                    Codec.STRING.fieldOf("text").forGetter(ChatMessage::text),
                    Codec.LONG.fieldOf("time").forGetter(ChatMessage::time)
            ).apply(instance, ChatMessage::new)
    );

    /**
     * 网络传输用。长度上限写在编解码器上，伪造客户端塞超长文本会在
     * 解码阶段就被拒收，轮不到业务层去发现。
     */
    public static final StreamCodec<ByteBuf, ChatMessage> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ChatMessage::sender,
                    ByteBufCodecs.stringUtf8(MAX_TEXT_LENGTH), ChatMessage::text,
                    ByteBufCodecs.VAR_LONG, ChatMessage::time,
                    ChatMessage::new
            );
}
