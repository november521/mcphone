package com.november.mcphone.network.chat;

import com.november.mcphone.chat.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 客户端本地的聊天数据缓存 —— 界面每帧从这里读，不发包。
 *
 * 刻意只放纯数据、不引用任何客户端专有的类：本类会被服务端侧的
 * 网络注册代码触及，若引入 Minecraft 客户端类，专用服务器会在类加载
 * 时直接崩溃。壁纸那边的 WakeholderData 是同样的理由。
 *
 * 数据由服务端下发的同步包填充，客户端自己从不构造——所有真值都在
 * 服务端，这里只是一份用于渲染的快照。
 *
 * 只缓存【当前打开的那一个】会话的消息，而不是所有会话：玩家一次只看
 * 一个会话，把全部历史都留在内存里没有意义。切换会话时整份替换。
 */
public final class ChatClientCache {

    private ChatClientCache() {}

    private static List<ConversationSummary> conversations = List.of();

    /** 当前打开的会话对端；null 表示没有打开任何会话 */
    private static UUID openPeer;

    /** 当前打开会话的消息，按时间升序 */
    private static List<ChatMessage> messages = List.of();

    // ============================================================
    //  会话列表
    // ============================================================

    public static List<ConversationSummary> getConversations() {
        return conversations;
    }

    static void setConversations(List<ConversationSummary> list) {
        conversations = List.copyOf(list);
    }

    // ============================================================
    //  当前会话
    // ============================================================

    public static UUID getOpenPeer() {
        return openPeer;
    }

    /**
     * 界面点进某个会话时调用，先把对端记下来。
     *
     * 必须在发请求【之前】调用：新消息推送可能在历史消息回来之前先到，
     * 那时若还不知道打开的是谁，这条消息就会被 appendMessage 丢掉。
     */
    public static void openConversation(UUID peer) {
        if (!java.util.Objects.equals(openPeer, peer)) {
            openPeer = peer;
            messages = List.of();   // 换会话先清空，免得闪出上一个会话的内容
        }
    }

    /** 界面退出会话时调用 */
    public static void closeConversation() {
        openPeer = null;
        messages = List.of();
    }

    public static List<ChatMessage> getMessages() {
        return messages;
    }

    static void setMessages(UUID peer, List<ChatMessage> list) {
        // 玩家可能在历史消息回来之前就退出或切换了会话，
        // 这时这份数据已经过期，直接丢弃而不是画到别人的会话里
        if (!java.util.Objects.equals(openPeer, peer)) return;
        messages = List.copyOf(list);
    }

    /**
     * 收到新消息。
     *
     * 只有正在看这个会话时才追加；否则不动——未读数会在下次拉取会话列表
     * 时由服务端算出来，不必在客户端自行维护一份，那样两边容易对不上。
     */
    static void appendMessage(UUID peer, ChatMessage message) {
        if (!java.util.Objects.equals(openPeer, peer)) return;

        List<ChatMessage> next = new ArrayList<>(messages);
        next.add(message);
        messages = List.copyOf(next);
    }

    /**
     * 退出世界时清空。
     *
     * 不清的话，换到另一个服务器时会先闪出上一个服务器的会话列表，
     * 那是别处的数据，既尴尬又可能泄露信息。
     */
    public static void clear() {
        conversations = List.of();
        closeConversation();
    }
}
