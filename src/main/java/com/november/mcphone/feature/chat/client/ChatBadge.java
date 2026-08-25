package com.november.mcphone.feature.chat.client;

import com.november.mcphone.feature.chat.net.ChatClientCache;
import com.november.mcphone.feature.chat.net.ConversationSummary;
import com.november.mcphone.feature.chat.net.RequestConversationsPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 主屏上美西螈图标右上角那个角标的数——所有会话的未读之和。
 *
 * 为什么问一次数就顺手发一个包
 *
 * 未读数服务端不推送，只在客户端来要摘要时现算（会话列表与会话页也是各自轮询）。
 * 而主屏并不开会话列表，不自己拉的话读到的永远是上次留下的旧数：手机开着不动，
 * 角标不会跟着新消息涨。
 *
 * 拉的时机就是主屏画到这个图标的那一刻——图标翻到了别的页、美西螈被卸了、
 * 手机根本没开，一个包都不会发。
 *
 * 3 秒一次，与会话列表同一个节奏；服务端另有 500 毫秒限流兜底，见
 * RequestThrottle.Kind.CONVERSATIONS。
 */
public final class ChatBadge {

    private static final long REFRESH_INTERVAL_MS = 3000L;

    private static long lastRequestMs;

    private ChatBadge() {}

    /** 未读总数，0 表示不画角标 */
    public static int unreadCount() {
        maybeRefresh();

        int total = 0;
        for (ConversationSummary c : ChatClientCache.getConversations()) {
            total += c.unread();
        }
        return total;
    }

    private static void maybeRefresh() {
        // 没连着服务器就没处发。正常进不来，但图标的画法不该假定这一点
        if (Minecraft.getInstance().getConnection() == null) return;

        long now = System.currentTimeMillis();
        if (now - lastRequestMs < REFRESH_INTERVAL_MS) return;

        lastRequestMs = now;
        PacketDistributor.sendToServer(new RequestConversationsPacket());
    }
}
