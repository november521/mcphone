package com.november.mcphone.feature.chat.client.messageaction;

import java.util.UUID;

/** 菜单打开时捕获不可变目标，不引用消息绘制器、输入框或可变消息下标。 */
record ChatMessageTarget(UUID peer, UUID messageId, String fullText, String selectedText) {
    boolean copyable() { return fullText != null; }
    String copiedText() { return selectedText.isEmpty() ? fullText : selectedText; }
}
