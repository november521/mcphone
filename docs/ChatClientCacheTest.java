package com.november.mcphone.feature.chat.net;

import com.november.mcphone.feature.chat.ChatData;
import com.november.mcphone.feature.chat.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 历史快照、切换会话与长时间收推送的容量回归。 */
public final class ChatClientCacheTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        UUID a = new UUID(0, 1), b = new UUID(0, 2);
        ChatClientCache.clear();
        ChatClientCache.openConversation(a);
        var history = new ArrayList<ChatMessage>();
        history.add(ChatMessage.text(a, "旧消息", 1));
        ChatClientCache.setMessages(a, history);
        history.clear();
        check(ChatClientCache.getMessages().size() == 1, "历史快照不受调用方修改影响");
        var snapshot = ChatClientCache.getMessages();
        for (int i = 2; i <= 250; i++) ChatClientCache.onNewMessage(a, ChatMessage.text(a, "消息" + i, i));
        var messages = ChatClientCache.getMessages();
        check(messages.size() == ChatData.MAX_MESSAGES_PER_CONVERSATION, "持续推送也只保留最近 100 条");
        check(messages.get(0).time() == 151 && messages.get(messages.size() - 1).time() == 250, "保留最新历史且顺序不变");
        check(snapshot.size() == 1, "追加不修改旧快照");
        ChatClientCache.onNewMessage(b, ChatMessage.text(b, "其他会话", 251));
        check(ChatClientCache.getMessages() == messages, "其他会话推送不污染当前历史");
        ChatClientCache.openConversation(b);
        ChatClientCache.setMessages(a, List.of(ChatMessage.text(a, "迟到的历史", 252)));
        check(ChatClientCache.getMessages().isEmpty(), "切换会话后丢弃旧历史响应");
        ChatClientCache.closeConversation();
        ChatClientCache.onNewMessage(b, ChatMessage.text(b, "关闭后消息", 253));
        check(ChatClientCache.getMessages().isEmpty(), "关闭会话后不积累历史");
        ChatClientCache.clear();
        check(ChatClientCache.getConversations().isEmpty() && ChatClientCache.getTotalOnline() == 0, "退出世界清理快照");
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
