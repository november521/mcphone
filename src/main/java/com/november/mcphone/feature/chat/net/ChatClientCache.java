package com.november.mcphone.feature.chat.net;

import com.november.mcphone.feature.chat.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import com.november.mcphone.feature.chat.net.ChatNetworking;
import com.november.mcphone.feature.chat.net.ConversationSummary;
import com.november.mcphone.feature.chat.net.OnlinePlayer;

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
/*
 * 【为什么在 net 包而不是 client 包】
 *
 * 它装的确实是客户端侧的状态，但它【一个客户端类型都不许出现】——同包的
 * ChatNetworking 是两端代码，服务端注册网络包时会加载它，而它的方法体里
 * 引用了本类。本类哪天多一句 import net.minecraft.client.*，专用服务器
 * 就会启动即崩，崩溃信息还不会提到聊天。
 *
 * 放进 client 包会让这条约束隐形：那个包的规矩恰恰是"可以引用客户端类型"。
 * 放在这里，扫描产物字节码时它和网络包受同一条规则约束，是机器可校验的。
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
     * 新消息到达时的旁听者。
     *
     * 用一个只认纯数据类型的回调，而不是让网络层直接去调客户端的通知
     * 代码：本类与 {@link ChatNetworking} 在专用服务器上也会被加载，
     * 一旦引入 Minecraft 客户端类就会在类加载时崩掉。
     *
     * 客户端启动时装上真正的实现（见 MCphoneClient），专用服务器上这里
     * 永远是个空实现，什么都不会发生。
     */
    private static BiConsumer<UUID, ChatMessage> messageListener = (peer, message) -> {};

    public static void setMessageListener(BiConsumer<UUID, ChatMessage> listener) {
        messageListener = listener;
    }

    /**
     * 收到一条新消息：正开着的会话要追加，旁听者要收到通知。
     *
     * 两件事都在这里做，网络层只管把包递进来。追加与通知的条件恰好相反
     * ——正看着才追加，没看着才提醒——分散在两处容易改一漏一。
     */
    static void onNewMessage(UUID peer, ChatMessage message) {
        appendMessage(peer, message);
        messageListener.accept(peer, message);
    }

    /**
     * 收到新消息。
     *
     * 只有正在看这个会话时才追加；否则不动——未读数会在下次拉取会话列表
     * 时由服务端算出来，不必在客户端自行维护一份，那样两边容易对不上。
     */
    private static void appendMessage(UUID peer, ChatMessage message) {
        if (!java.util.Objects.equals(openPeer, peer)) return;

        List<ChatMessage> next = new ArrayList<>(messages);
        next.add(message);
        messages = List.copyOf(next);
    }

    // ============================================================
    //  在线玩家（"加联系人"界面用）
    // ============================================================

    private static List<OnlinePlayer> onlinePlayers = List.of();
    private static int totalOnline;

    public static List<OnlinePlayer> getOnlinePlayers() {
        return onlinePlayers;
    }

    /** 服务器实际在线人数（不含本人）。大于列表长度即说明被截断过 */
    public static int getTotalOnline() {
        return totalOnline;
    }

    /** 列表是否被截断 —— 界面据此显示"显示前 N 人 / 共 M 人" */
    public static boolean isOnlineListTruncated() {
        return totalOnline > onlinePlayers.size();
    }

    static void setOnlinePlayers(List<OnlinePlayer> list, int total) {
        onlinePlayers = List.copyOf(list);
        totalOnline = total;
    }

    /**
     * 退出世界时清空。
     *
     * 不清的话，换到另一个服务器时会先闪出上一个服务器的会话列表，
     * 那是别处的数据，既尴尬又可能泄露信息。
     */
    public static void clear() {
        conversations = List.of();
        onlinePlayers = List.of();
        totalOnline = 0;
        closeConversation();
    }
}
