package com.november.mcphone.feature.chat;

import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.feature.chat.net.ConversationSummary;
import com.november.mcphone.feature.chat.net.OnlinePlayer;
import com.november.mcphone.feature.chat.net.Relation;
import com.november.mcphone.util.TextSanitizer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 聊天与好友的服务端业务逻辑，只回答"结果是什么"，不关心传输。
 * 好友是双向的：申请 → 对方同意 → 互为好友；非好友之间不能聊天。
 */
public final class ChatService {

    private ChatService() {}

    /** 会话列表就是好友列表；解除好友后会话消失但记录仍在。顺带把在线好友的名字记进缓存 */
    public static List<ConversationSummary> buildConversations(ServerPlayer self) {
        MinecraftServer server = self.server;
        UUID selfId = self.getUUID();

        ChatData chat = ChatData.get(server);
        FriendData friends = FriendData.get(server);
        ChatReadState read = self.getData(ModAttachments.CHAT_READ.get());

        List<ConversationSummary> out = new ArrayList<>();
        for (UUID peer : friends.getFriends(selfId)) {
            ServerPlayer online = server.getPlayerList().getPlayer(peer);
            boolean isOnline = online != null;

            String name = resolveName(server, friends, peer, online);
            if (isOnline) friends.rememberName(peer, name);

            ChatData.Tail tail = chat.tail(selfId, peer, read.getLastRead(peer));

            out.add(tail.last() == null
                    ? ConversationSummary.empty(peer, name, isOnline)
                    : new ConversationSummary(peer, name, isOnline,
                            tail.last().text(), tail.last().time(), tail.unread()));
        }

        // 有消息的按最后一条时间倒序，没聊过的沉底
        out.sort(Comparator.comparingLong(ConversationSummary::lastTime).reversed());
        return out;
    }

    /**
     * 时间戳由服务端盖章，不采信客户端（客户端的钟能把自己的消息永远顶在列表最前）。
     * 返回落库后的消息，未通过校验返回 null。
     */
    public static ChatMessage sendMessage(ServerPlayer sender, UUID targetId, String rawText) {
        if (!FriendGuard.mayActOn(sender, targetId)) return null;

        UUID senderId = sender.getUUID();
        String text = TextSanitizer.sanitize(rawText, ChatMessage.MAX_TEXT_LENGTH);
        if (text.isEmpty()) return null;

        ChatMessage message = new ChatMessage(senderId, text, System.currentTimeMillis());
        ChatData.get(sender.server).addMessage(senderId, targetId, message);

        // 自己发的对自己是已读的，否则对方一回复就把中间这段全算成未读
        markReadAt(sender, targetId, message.time());
        return message;
    }

    /** 非好友一律返回空，免得解除好友后还能翻旧账 */
    public static List<ChatMessage> getMessages(ServerPlayer self, UUID peer) {
        if (!FriendData.get(self.server).areFriends(self.getUUID(), peer)) return List.of();
        return ChatData.get(self.server).getMessages(self.getUUID(), peer);
    }

    /** 已读时刻由服务端盖章：采信客户端的话报一个未来时间就能让红点永远不出现 */
    public static void markRead(ServerPlayer self, UUID peer) {
        markReadAt(self, peer, System.currentTimeMillis());
    }

    private static void markReadAt(ServerPlayer self, UUID peer, long time) {
        ChatReadState read = self.getData(ModAttachments.CHAT_READ.get());
        ChatReadState updated = read.withLastRead(peer, time);
        if (updated != read) {
            self.setData(ModAttachments.CHAT_READ.get(), updated);
        }
    }

    /** 对方已经申请过我时直接成为好友，不再挂一条反向申请 */
    public static FriendOutcome sendFriendRequest(ServerPlayer self, UUID targetId) {
        if (!FriendGuard.carriesPhone(self)) return FriendOutcome.NOTHING;

        UUID selfId = self.getUUID();
        if (selfId.equals(targetId)) return FriendOutcome.NOTHING;

        MinecraftServer server = self.server;
        FriendData friends = FriendData.get(server);

        if (friends.areFriends(selfId, targetId)) return FriendOutcome.NOTHING;
        if (friends.countFriends(selfId) >= FriendData.MAX_FRIENDS) return FriendOutcome.SELF_FULL;
        if (friends.countFriends(targetId) >= FriendData.MAX_FRIENDS) return FriendOutcome.PEER_FULL;

        if (friends.hasRequest(targetId, selfId)) {
            friends.removeRequest(targetId, selfId);
            friends.addFriendship(selfId, targetId);
            rememberBoth(server, friends, selfId, targetId);
            return FriendOutcome.OK;
        }

        // 只能申请服务端见过的人，否则可以对着编造的 UUID 无限发申请把存档撑大
        if (!isKnownPlayer(server, friends, targetId)) return FriendOutcome.UNKNOWN_PLAYER;

        if (!friends.addRequest(selfId, targetId, System.currentTimeMillis())) {
            return FriendOutcome.PEER_INBOX_FULL;
        }
        rememberBoth(server, friends, selfId, targetId);
        return FriendOutcome.OK;
    }

    /**
     * 同意与拒绝共用校验：申请必须存在且是发给我的。
     * 上限检查必须在删申请之前，否则满员时申请被吃掉、好友也没加上、两边都没提示。
     */
    public static FriendOutcome respondFriendRequest(ServerPlayer self, UUID requesterId,
                                                     boolean accept) {
        if (!FriendGuard.carriesPhone(self)) return FriendOutcome.NOTHING;

        UUID selfId = self.getUUID();
        FriendData friends = FriendData.get(self.server);

        // 拦住伪造客户端凭空"同意"一条不存在的申请
        if (!friends.hasRequest(requesterId, selfId)) return FriendOutcome.NOTHING;

        if (!accept) {
            friends.removeRequest(requesterId, selfId);
            return FriendOutcome.OK;
        }

        if (friends.countFriends(selfId) >= FriendData.MAX_FRIENDS) {
            return FriendOutcome.SELF_FULL;
        }
        if (friends.countFriends(requesterId) >= FriendData.MAX_FRIENDS) {
            return FriendOutcome.PEER_FULL;
        }

        friends.removeRequest(requesterId, selfId);
        friends.addFriendship(selfId, requesterId);
        rememberBoth(self.server, friends, selfId, requesterId);
        return FriendOutcome.OK;
    }

    /** 双向解除并清掉本人的已读进度；聊天记录不删，那是双方共有的 */
    public static boolean removeFriend(ServerPlayer self, UUID targetId) {
        if (!FriendGuard.carriesPhone(self)) return false;

        FriendData friends = FriendData.get(self.server);
        if (!friends.removeFriendship(self.getUUID(), targetId)) return false;

        ChatReadState read = self.getData(ModAttachments.CHAT_READ.get());
        ChatReadState updated = read.without(targetId);
        if (updated != read) {
            self.setData(ModAttachments.CHAT_READ.get(), updated);
        }
        return true;
    }

    /**
     * 在线玩家列表，排除本人，超过 limit 截断（总数由调用方另取）。
     * 顺带把每个在线玩家的名字记进缓存：这是唯一能可靠拿到"UUID 对应谁"的时机。
     */
    public static List<OnlinePlayer> listOnlinePlayers(ServerPlayer self, int limit) {
        UUID selfId = self.getUUID();
        FriendData friends = FriendData.get(self.server);

        List<OnlinePlayer> out = new ArrayList<>();
        for (ServerPlayer p : self.server.getPlayerList().getPlayers()) {
            UUID id = p.getUUID();
            if (id.equals(selfId)) continue;

            String name = ConversationSummary.clampName(p.getGameProfile().getName());
            friends.rememberName(id, name);

            if (out.size() >= limit) continue;   // 仍要走完循环，名字缓存不能漏
            out.add(new OnlinePlayer(id, name, relationTo(friends, selfId, id)));
        }
        return out;
    }

    /** 在线人数（不含本人），用于告知界面列表被截断了多少 */
    public static int countOnlineExcludingSelf(ServerPlayer self) {
        return Math.max(0, self.server.getPlayerList().getPlayerCount() - 1);
    }

    public static Relation relationTo(FriendData friends, UUID selfId, UUID otherId) {
        if (friends.areFriends(selfId, otherId)) return Relation.FRIEND;
        if (friends.hasRequest(selfId, otherId)) return Relation.REQUEST_SENT;
        if (friends.hasRequest(otherId, selfId)) return Relation.REQUEST_RECEIVED;
        return Relation.NONE;
    }

    /** 在线，或名字缓存/资料缓存里有记录 */
    private static boolean isKnownPlayer(MinecraftServer server, FriendData friends, UUID id) {
        if (server.getPlayerList().getPlayer(id) != null) return true;
        if (friends.getName(id) != null) return true;

        GameProfileCache cache = server.getProfileCache();
        return cache != null && cache.get(id).isPresent();
    }

    /** 建立关系时把双方的名字都记一遍，日后任一方离线都显示得出来 */
    private static void rememberBoth(MinecraftServer server, FriendData friends,
                                     UUID a, UUID b) {
        friends.rememberName(a, resolveName(server, friends, a, server.getPlayerList().getPlayer(a)));
        friends.rememberName(b, resolveName(server, friends, b, server.getPlayerList().getPlayer(b)));
    }

    /** 依次试：在线真名 → 名字缓存 → 资料缓存 → UUID 前 8 位。截断只在这一处收口 */
    public static String resolveName(MinecraftServer server, FriendData friends,
                                     UUID id, ServerPlayer online) {
        return ConversationSummary.clampName(rawName(server, friends, id, online));
    }

    /** 原样结果不截断；超长名字进了网络包会在编码阶段抛异常打断连接，所以由 resolveName 统一截 */
    private static String rawName(MinecraftServer server, FriendData friends,
                                  UUID id, ServerPlayer online) {
        if (online != null) {
            return online.getGameProfile().getName();
        }

        String cached = friends.getName(id);
        if (cached != null) return cached;

        GameProfileCache cache = server.getProfileCache();
        if (cache != null) {
            var profile = cache.get(id);
            if (profile.isPresent()) return profile.get().getName();
        }

        return id.toString().substring(0, 8);
    }
}
