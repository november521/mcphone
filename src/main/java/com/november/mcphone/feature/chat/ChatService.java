package com.november.mcphone.feature.chat;

import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.core.ServerConfig;
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
import java.util.Optional;
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
                            Optional.of(tail.last().body()), tail.last().time(), tail.unread()));
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
        String text = TextSanitizer.sanitize(rawText, TextBody.MAX_LENGTH);
        if (text.isEmpty()) return null;

        ChatMessage message = ChatMessage.text(senderId, text, System.currentTimeMillis());
        store(sender, targetId, message);

        // 自己发的对自己是已读的，否则对方一回复就把中间这段全算成未读
        markReadAt(sender, targetId, message.time());
        return message;
    }

    /**
     * 发图片前的门禁，在【动硬盘之前】问。
     *
     * 与 {@link #sendImage} 分开是因为中间隔着一次写文件，而那一次要挪到后台线程去做
     * （见 ChatNetworking）：先在主线程把该拒的拒掉，别为一个连好友都不是的人写盘。
     */
    public static ImageOutcome maySendImage(ServerPlayer sender, UUID targetId) {
        if (!ServerConfig.allowChatImages()) return ImageOutcome.DISABLED;
        if (!FriendGuard.mayActOn(sender, targetId)) return ImageOutcome.NOTHING;
        return ImageOutcome.OK;
    }

    /**
     * 图片已经写进图片仓了，这里把对应的那条消息落库。返回落库后的消息，没通过校验返回 null。
     *
     * 校验要在这里【再做一遍】：写盘那一下是异步的，其间玩家可能已经把手机丢了、
     * 或者被对方解除了好友。返回 null 时调用方负责把刚写下的那张图删掉。
     */
    public static ChatMessage sendImage(ServerPlayer sender, UUID targetId,
                                        UUID imageId, int width, int height) {
        if (maySendImage(sender, targetId) != ImageOutcome.OK) return null;

        UUID senderId = sender.getUUID();
        ChatMessage message = new ChatMessage(senderId, System.currentTimeMillis(),
                new ImageBody(imageId, width, height));
        store(sender, targetId, message);
        trimImages(sender.server, senderId, targetId);

        markReadAt(sender, targetId, message.time());
        return message;
    }

    /**
     * 这个人能不能要这张图。
     *
     * 判据与拉历史消息（{@link #getMessages}）完全一致：得是好友，且这张图确实出现在
     * 他与对方的那段记录里。不这么判的话，知道一个图片 id 就能把别人的图要走——
     * id 是随机 UUID，猜不出来，但它会随消息一起下发给会话的另一方，而"另一方"
     * 可能日后解除了好友。
     *
     * 现扫这一段记录（至多 100 条）而不是另建索引，理由与 {@link ChatData#referencedImages}
     * 那条一样：多一份要保持同步的东西，就多一处会悄悄对不上的地方。
     */
    public static boolean mayReadImage(ServerPlayer self, UUID peer, UUID imageId) {
        if (!FriendData.get(self.server).areFriends(self.getUUID(), peer)) return false;

        for (ChatMessage m : ChatData.get(self.server).getMessages(self.getUUID(), peer)) {
            if (m.body() instanceof ImageBody image && image.image().equals(imageId)) return true;
        }
        return false;
    }

    /** 落库；被 100 条上限挤出去的若是图片消息，它那张图跟着删——没有消息认领的像素只是垃圾 */
    private static void store(ServerPlayer sender, UUID targetId, ChatMessage message) {
        List<ChatMessage> evicted =
                ChatData.get(sender.server).addMessage(sender.getUUID(), targetId, message);

        for (ChatMessage old : evicted) {
            if (old.body() instanceof ImageBody image) {
                ChatImageStore.delete(sender.server, image.image());
            }
        }
    }

    /**
     * 一对会话里的图超过上限时，把最旧的那几张的像素删掉。
     *
     * 只删像素、不删消息：那条消息还在记录里，界面显示成「图片已过期」。删掉整条的话，
     * 聊天记录会凭空少几行，而少的是什么谁也不知道——玩家只会觉得"我记得这儿说过话"。
     */
    private static void trimImages(MinecraftServer server, UUID a, UUID b) {
        List<ChatMessage> messages = ChatData.get(server).getMessages(a, b);

        int images = 0;
        for (ChatMessage m : messages) {
            if (m.body() instanceof ImageBody) images++;
        }

        int excess = images - ChatImage.MAX_IMAGES_PER_CONVERSATION;
        if (excess <= 0) return;

        // 列表是时间升序，从头删就是先删最旧的。已经过期的再删一次是无害的空操作
        for (ChatMessage m : messages) {
            if (excess <= 0) break;
            if (m.body() instanceof ImageBody image) {
                ChatImageStore.delete(server, image.image());
                excess--;
            }
        }
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
