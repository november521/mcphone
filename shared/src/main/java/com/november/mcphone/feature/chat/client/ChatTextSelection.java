package com.november.mcphone.feature.chat.client;

import java.util.List;
import java.util.UUID;
import com.november.mcphone.feature.chat.ChatMessage;

/** 一个会话页的只读选区。稳定消息 ID 隔离同名/同时间消息，不依赖会变动的列表下标。 */
final class ChatTextSelection {
    private UUID message;
    private ChatTextLayout text;
    private int anchor, caret;
    private boolean dragging;

    void begin(UUID message, ChatTextLayout text, int index) {
        this.message = message;
        this.text = text;
        anchor = caret = index;
        dragging = true;
    }
    void extend(int index) { if (dragging) caret = index; }
    boolean release() {
        boolean consumed = dragging;
        dragging = false;
        return consumed;
    }
    boolean dragging() { return dragging; }
    boolean owns(UUID candidate) { return message != null && message.equals(candidate); }
    int start() { return Math.min(anchor, caret); }
    int end() { return Math.max(anchor, caret); }
    String selectedText() { return text == null ? "" : text.source().substring(start(), end()); }
    void reconcile(List<ChatMessage> messages) {
        if (message != null && messages.stream().noneMatch(m -> owns(m.id()))) clear();
    }
    void clear() { message = null; text = null; anchor = caret = 0; dragging = false; }
}
