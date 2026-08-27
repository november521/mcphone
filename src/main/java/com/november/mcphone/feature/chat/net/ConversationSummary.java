package com.november.mcphone.feature.chat.net;

import com.november.mcphone.feature.chat.ChatMessage;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/**
 * 会话列表里的一行摘要；历史消息等玩家点进会话再单独拉。
 * StreamCodec 手写而不用 composite：composite 最多 6 个字段，本记录正好 6 个，用它以后加字段就得推倒重写。
 * online 是瞬时状态，现算现发不落盘；lastText 空串、lastTime 0 表示还没聊过。
 */
public record ConversationSummary(UUID id, String name, boolean online,
                                  String lastText, long lastTime, int unread) {

    /**
     * 玩家名长度上限。原版名最长 16，但这是编解码器的硬上限，超了 writeUtf 直接抛异常断线，
     * 而 Geyser 前缀、离线模式、代理都可能给出超过 16 的名字，所以放宽到 32 并由 {@link #clampName} 兜底。
     */
    public static final int MAX_NAME_LENGTH = 32;

    /** 所有下发给客户端的名字都要过这一道；按字符数截，32 字符最多 96 字节，在 writeUtf 的字节上限内 */
    public static String clampName(String name) {
        if (name == null || name.isEmpty()) return "";
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
    }

    public static final StreamCodec<ByteBuf, ConversationSummary> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ConversationSummary decode(ByteBuf buf) {
                    UUID id = UUIDUtil.STREAM_CODEC.decode(buf);
                    String name = ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH).decode(buf);
                    boolean online = ByteBufCodecs.BOOL.decode(buf);
                    String lastText = ByteBufCodecs.stringUtf8(ChatMessage.MAX_TEXT_LENGTH).decode(buf);
                    long lastTime = ByteBufCodecs.VAR_LONG.decode(buf);
                    int unread = ByteBufCodecs.VAR_INT.decode(buf);
                    return new ConversationSummary(id, name, online, lastText, lastTime, unread);
                }

                @Override
                public void encode(ByteBuf buf, ConversationSummary value) {
                    UUIDUtil.STREAM_CODEC.encode(buf, value.id());
                    ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH).encode(buf, value.name());
                    ByteBufCodecs.BOOL.encode(buf, value.online());
                    ByteBufCodecs.stringUtf8(ChatMessage.MAX_TEXT_LENGTH).encode(buf, value.lastText());
                    ByteBufCodecs.VAR_LONG.encode(buf, value.lastTime());
                    ByteBufCodecs.VAR_INT.encode(buf, value.unread());
                }
            };

    /** 还没聊过的联系人 */
    public static ConversationSummary empty(UUID id, String name, boolean online) {
        return new ConversationSummary(id, name, online, "", 0L, 0);
    }
}
