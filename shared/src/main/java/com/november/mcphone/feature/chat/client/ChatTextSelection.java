package com.november.mcphone.feature.chat.client;

import java.util.List;

/** 一个会话页的只读选区。消息对象身份隔离同名/同时间消息，不依赖会变动的列表下标。 */
final class ChatTextSelection {
    private Object message;
    private ChatTextLayout text;
    private int anchor, caret;
    private boolean dragging;

    void begin(Object message, ChatTextLayout text, int index) {
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
    boolean owns(Object candidate) { return message != null && message == candidate; }
    int start() { return Math.min(anchor, caret); }
    int end() { return Math.max(anchor, caret); }
    String selectedText() { return text == null ? "" : text.source().substring(start(), end()); }
    void reconcile(List<?> messages) {
        if (message != null && messages.stream().noneMatch(this::owns)) clear();
    }
    void clear() { message = null; text = null; anchor = caret = 0; dragging = false; }
}
