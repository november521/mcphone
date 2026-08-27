package com.november.mcphone.feature.chat.net;

import com.november.mcphone.feature.chat.ChatMessage;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * 会话列表里的一行摘要；历史消息等玩家点进会话再单独拉。
 * 编解码手写而不用固定字段组合：1.21.1 那边 composite 最多 6 个字段，本记录正好 6 个，用它以后加字段就得推倒重写。
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

    public static void encode(ConversationSummary value, FriendlyByteBuf buf) {
        buf.writeUUID(value.id());
        buf.writeUtf(value.name(), MAX_NAME_LENGTH);
        buf.writeBoolean(value.online());
        buf.writeUtf(value.lastText(), ChatMessage.MAX_TEXT_LENGTH);
        buf.writeVarLong(value.lastTime());
        buf.writeVarInt(value.unread());
    }

    public static ConversationSummary decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf(MAX_NAME_LENGTH);
        boolean online = buf.readBoolean();
        String lastText = buf.readUtf(ChatMessage.MAX_TEXT_LENGTH);
        long lastTime = buf.readVarLong();
        int unread = buf.readVarInt();
        return new ConversationSummary(id, name, online, lastText, lastTime, unread);
    }

    /** 还没聊过的联系人 */
    public static ConversationSummary empty(UUID id, String name, boolean online) {
        return new ConversationSummary(id, name, online, "", 0L, 0);
    }
}
