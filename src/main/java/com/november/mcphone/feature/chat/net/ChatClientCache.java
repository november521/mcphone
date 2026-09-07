package com.november.mcphone.feature.chat.net;

import com.november.mcphone.feature.chat.ChatMessage;
import com.november.mcphone.feature.chat.net.ConversationSummary;
import com.november.mcphone.feature.chat.net.OnlinePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * 客户端本地的聊天数据缓存，界面每帧从这里读；真值全在服务端，这只是渲染快照。
 * 故意放在 net 包且一个客户端类型都不许出现：同包的 ChatNetworking 在专用服务器上会加载它，多一句 import net.minecraft.client.* 就启动即崩。
 * 只缓存当前打开的那一个会话的消息，切换时整份替换。
 */
public final class ChatClientCache {

    private ChatClientCache() {}

    private static List<ConversationSummary> conversations = List.of();

    /** 当前打开的会话对端；null 表示没有打开任何会话 */
    private static UUID openPeer;

    /** 当前打开会话的消息，按时间升序 */
    private static List<ChatMessage> messages = List.of();

    public static List<ConversationSummary> getConversations() {
        return conversations;
    }

    static void setConversations(List<ConversationSummary> list) {
        conversations = List.copyOf(list);
    }

    /** 必须在发请求之前调用：新消息推送可能先于历史消息到达，不先记下对端会被 appendMessage 丢掉 */
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
        // 玩家可能在数据回来之前已退出或切换会话，过期数据直接丢弃
        if (!java.util.Objects.equals(openPeer, peer)) return;
        messages = List.copyOf(list);
    }

    /** 客户端启动时装上真正的实现（见 MCphoneClient）；只认纯数据类型，专用服务器上永远是空实现 */
    private static BiConsumer<UUID, ChatMessage> messageListener = (peer, message) -> {};

    public static void setMessageListener(BiConsumer<UUID, ChatMessage> listener) {
        messageListener = listener;
    }

    /**
     * 图片像素到了。同样走监听器：解码与贴图是客户端的事，本类连 import 都不能有。
     * data 为空数组表示服务端说这张图没了，见 ChatImageDataPacket。
     */
    private static BiConsumer<UUID, byte[]> imageListener = (image, data) -> {};

    public static void setImageListener(BiConsumer<UUID, byte[]> listener) {
        imageListener = listener;
    }

    static void onImageData(UUID image, byte[] data) {
        imageListener.accept(image, data);
    }

    /** 追加与通知的条件恰好相反（正看着才追加，没看着才提醒），放一起免得改一漏一 */
    static void onNewMessage(UUID peer, ChatMessage message) {
        appendMessage(peer, message);
        messageListener.accept(peer, message);
    }

    /** 只有正在看这个会话时才追加；未读数由服务端算，客户端不自行维护 */
    private static void appendMessage(UUID peer, ChatMessage message) {
        if (!java.util.Objects.equals(openPeer, peer)) return;

        List<ChatMessage> next = new ArrayList<>(messages);
        next.add(message);
        messages = List.copyOf(next);
    }

    private static List<OnlinePlayer> onlinePlayers = List.of();
    private static int totalOnline;

    public static List<OnlinePlayer> getOnlinePlayers() {
        return onlinePlayers;
    }

    /** 服务器实际在线人数（不含本人）。大于列表长度即说明被截断过 */
    public static int getTotalOnline() {
        return totalOnline;
    }

    /** 界面据此显示"显示前 N 人 / 共 M 人" */
    public static boolean isOnlineListTruncated() {
        return totalOnline > onlinePlayers.size();
    }

    static void setOnlinePlayers(List<OnlinePlayer> list, int total) {
        onlinePlayers = List.copyOf(list);
        totalOnline = total;
    }

    /** 退出世界时清空，免得换服务器时闪出上一个服务器的数据 */
    public static void clear() {
        conversations = List.of();
        onlinePlayers = List.of();
        totalOnline = 0;
        closeConversation();
    }
}
