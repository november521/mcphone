package com.november.mcphone.network.chat;

import java.util.List;

/**
 * 客户端本地的聊天数据缓存 —— 界面每帧从这里读，不发包。
 *
 * 刻意只放纯数据、不引用任何客户端专有的类：本类会被服务端侧的
 * 网络注册代码触及，若引入 Minecraft 客户端类，专用服务器会在类加载
 * 时直接崩溃。壁纸那边的 WakeholderData 是同样的理由。
 *
 * 数据由服务端下发的同步包填充，客户端自己从不构造——所有真值都在
 * 服务端，这里只是一份用于渲染的快照。
 */
public final class ChatClientCache {

    private ChatClientCache() {}

    private static List<ConversationSummary> conversations = List.of();

    public static List<ConversationSummary> getConversations() {
        return conversations;
    }

    static void setConversations(List<ConversationSummary> list) {
        conversations = List.copyOf(list);
    }

    /**
     * 退出世界时清空。
     *
     * 不清的话，换到另一个服务器时会先闪出上一个服务器的会话列表，
     * 那是别处的数据，既尴尬又可能泄露信息。
     */
    public static void clear() {
        conversations = List.of();
    }
}
