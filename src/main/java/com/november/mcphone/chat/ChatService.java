package com.november.mcphone.chat;

import com.november.mcphone.ModAttachments;
import com.november.mcphone.network.chat.ConversationSummary;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 聊天业务逻辑 —— 服务端侧。
 *
 * 刻意与网络包分开：本类只回答"结果是什么"，不关心它怎么被传输。
 * 这样加新入口（命令、其他 App、附属模组）时不必绕道网络层，
 * 改传输格式时也不会碰到业务规则。
 */
public final class ChatService {

    private ChatService() {}

    /**
     * 构建某玩家的会话列表。
     *
     * 会话列表 = 联系人 ∪ 有消息往来的人。后者不能漏：别人给你发了消息
     * 但你还没把他加为联系人时，消息不能凭空消失——那条会话要出现在
     * 列表里，界面上再提供"加为联系人"。
     *
     * 顺带做一件事：对在线联系人刷新显示名。玩家可能改过名，而联系人
     * 里存的是加好友当时的名字。只在真的变了时才写回，避免每次打开
     * 聊天都把附件标脏、引发无谓的存档写入。
     */
    public static List<ConversationSummary> buildConversations(ServerPlayer self) {
        MinecraftServer server = self.server;
        UUID selfId = self.getUUID();

        ChatData chat = ChatData.get(server);
        ContactsData contacts = self.getData(ModAttachments.CONTACTS.get());

        // LinkedHashSet：联系人在前、陌生人在后，且不重复
        Set<UUID> peers = new LinkedHashSet<>();
        for (Contact c : contacts.contacts()) peers.add(c.id());
        peers.addAll(chat.getPeers(selfId));

        ContactsData refreshed = contacts;
        List<ConversationSummary> out = new ArrayList<>(peers.size());

        for (UUID peer : peers) {
            ServerPlayer online = server.getPlayerList().getPlayer(peer);
            boolean isOnline = online != null;

            String name = resolveName(server, refreshed, peer, online);
            if (isOnline) {
                // 只在名字真的变了时才产生新实例
                refreshed = refreshed.withRefreshedName(peer, name);
            }

            ChatMessage last = chat.getLastMessage(selfId, peer);
            int unread = chat.countAfter(selfId, peer, refreshed.getLastRead(peer));

            out.add(last == null
                    ? ConversationSummary.empty(peer, name, isOnline)
                    : new ConversationSummary(peer, name, isOnline,
                            last.text(), last.time(), unread));
        }

        if (refreshed != contacts) {
            self.setData(ModAttachments.CONTACTS.get(), refreshed);
        }

        // 有消息的按最后一条时间倒序排在前，没聊过的沉底
        out.sort(Comparator.comparingLong(ConversationSummary::lastTime).reversed());
        return out;
    }

    /**
     * 把 UUID 解析成显示名，按可靠性依次尝试：
     *
     *   1. 对方在线 —— 用当前真名，最准
     *   2. 联系人里存过的名字 —— 离线也认得出是谁，这正是存它的理由
     *   3. 服务端资料缓存 —— 陌生人（没加为联系人但给你发过消息）走这条
     *   4. 都没有 —— 退回 UUID 前 8 位，至少不是空白
     *
     * 第 4 条不该出现，但宁可显示得难看，也不要让界面上出现空名字或
     * 抛异常。
     */
    private static String resolveName(MinecraftServer server, ContactsData contacts,
                                      UUID peer, ServerPlayer online) {
        if (online != null) {
            return online.getGameProfile().getName();
        }

        var stored = contacts.findContact(peer);
        if (stored.isPresent()) {
            return stored.get().name();
        }

        GameProfileCache cache = server.getProfileCache();
        if (cache != null) {
            var profile = cache.get(peer);
            if (profile.isPresent()) {
                return profile.get().getName();
            }
        }

        return peer.toString().substring(0, 8);
    }
}
