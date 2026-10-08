package com.november.mcphone.feature.chat.client;

import com.november.mcphone.feature.chat.ChatMessage;
import java.util.UUID;

/** 客户端消息事件入口。上传确认先处理，通知过滤不能阻断发送状态收尾。 */
public final class ChatClientEvents {
    private ChatClientEvents() {}

    public static void onMessage(UUID peer, ChatMessage message) {
        ChatImageSender.onNewMessage(message);
        ChatNotifier.onMessage(peer, message);
    }
}
