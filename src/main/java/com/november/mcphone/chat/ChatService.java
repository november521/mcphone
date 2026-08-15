package com.november.mcphone.chat;

import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.core.PhoneItem;
import com.november.mcphone.network.chat.ConversationSummary;
import com.november.mcphone.network.chat.OnlinePlayer;
import com.november.mcphone.network.chat.Relation;
import com.november.mcphone.util.TextSanitizer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 聊天与好友的业务逻辑 —— 服务端侧。
 *
 * 刻意与网络包分开：本类只回答"结果是什么"，不关心它怎么被传输。
 * 这样加新入口（命令、其他 App、附属模组）时不必绕道网络层，
 * 改传输格式时也不会碰到业务规则。
 *
 * 好友是双向的：申请 → 对方同意 → 双方互为好友。非好友之间不能聊天。
 */
public final class ChatService {

    private ChatService() {}

    // ============================================================
    //  会话列表
    // ============================================================

    /**
     * 构建某玩家的会话列表 —— 就是他的好友列表。
     *
     * 非好友不会出现：聊天限定在好友之间，陌生人根本发不进来消息。
     * 解除好友后那条会话也随之消失，但聊天记录仍在存储里，重新加回来
     * 历史就回来了。
     *
     * 顺带把在线好友的名字记进缓存，供他们离线时显示。
     */
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

            ChatMessage last = chat.getLastMessage(selfId, peer);
            int unread = chat.countAfter(selfId, peer, read.getLastRead(peer));

            out.add(last == null
                    ? ConversationSummary.empty(peer, name, isOnline)
                    : new ConversationSummary(peer, name, isOnline,
                            last.text(), last.time(), unread));
        }

        // 有消息的按最后一条时间倒序排在前，没聊过的沉底
        out.sort(Comparator.comparingLong(ConversationSummary::lastTime).reversed());
        return out;
    }

    // ============================================================
    //  消息
    // ============================================================

    /**
     * 发一条消息。所有规则集中在这里，网络层只管收包与回包。
     *
     * 校验逐条都有原因，少一条都能被伪造客户端利用：
     *
     *   - 身上得有手机：没有这条，改个客户端就能凭空聊天，
     *     "得有一部手机"这个前提形同虚设。拿在手上、放在背包、挂在
     *     饰品槽都算，见 PhoneItem.isCarriedBy
     *   - 收件人必须是好友：这是双向好友模型的意义所在。同时堵掉了
     *     "对着随便编造的 UUID 发消息"——那既能把存档撑满，
     *     又能给素未谋面的玩家凭空塞一条会话
     *   - 正文清洗后不能为空：纯空格或纯格式符的消息没有意义
     *
     * 不必单独判断"不能发给自己"：自己不会是自己的好友，上面那条已经拦住了。
     *
     * 时间戳由服务端盖章，不采信客户端——客户端的钟不可信，早一点晚一点
     * 会打乱会话排序，极端情况下能把自己的消息永远顶在列表最前。
     *
     * @return 落库后的消息；未通过校验时返回 null
     */
    public static ChatMessage sendMessage(ServerPlayer sender, UUID targetId, String rawText) {
        if (!PhoneItem.isCarriedBy(sender)) return null;

        UUID senderId = sender.getUUID();
        if (!FriendData.get(sender.server).areFriends(senderId, targetId)) return null;

        String text = TextSanitizer.sanitize(rawText, ChatMessage.MAX_TEXT_LENGTH);
        if (text.isEmpty()) return null;

        ChatMessage message = new ChatMessage(senderId, text, System.currentTimeMillis());
        ChatData.get(sender.server).addMessage(senderId, targetId, message);

        // 自己发的消息对自己而言当然是已读的。不推进已读时刻的话，
        // 对方回复一到就会把中间这段全算成未读
        markReadAt(sender, targetId, message.time());
        return message;
    }

    /** 取某个会话的历史消息。非好友一律返回空，免得解除好友后还能翻旧账 */
    public static List<ChatMessage> getMessages(ServerPlayer self, UUID peer) {
        if (!FriendData.get(self.server).areFriends(self.getUUID(), peer)) return List.of();
        return ChatData.get(self.server).getMessages(self.getUUID(), peer);
    }

    /**
     * 把与某人的会话标为已读到此刻。
     *
     * 已读时刻由服务端盖章而非采信客户端：客户端报一个未来的时间戳就能
     * 把之后收到的消息全标成已读，红点再也不出现。
     */
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

    // ============================================================
    //  好友
    // ============================================================

    /**
     * 发出好友申请。
     *
     * 一条捷径：如果对方【已经】给我发过申请，这里直接成为好友，
     * 而不是再挂一条反向申请。两个人同时点了"添加"却谁也加不上，
     * 是很蠢的体验。
     *
     * @return 是否产生了变化（发出申请，或直接成为好友）
     */
    public static boolean sendFriendRequest(ServerPlayer self, UUID targetId) {
        if (!PhoneItem.isCarriedBy(self)) return false;

        UUID selfId = self.getUUID();
        if (selfId.equals(targetId)) return false;

        MinecraftServer server = self.server;
        FriendData friends = FriendData.get(server);

        if (friends.areFriends(selfId, targetId)) return false;
        if (friends.countFriends(selfId) >= FriendData.MAX_FRIENDS) return false;
        if (friends.countFriends(targetId) >= FriendData.MAX_FRIENDS) return false;

        // 对方先申请过我 → 直接成为好友，省掉一次来回
        if (friends.hasRequest(targetId, selfId)) {
            friends.removeRequest(targetId, selfId);
            friends.addFriendship(selfId, targetId);
            rememberBoth(server, friends, selfId, targetId);
            return true;
        }

        // 只能申请"存在的人"：在线，或服务端见过（有名字缓存）。
        // 否则可以对着编造的 UUID 无限发申请，把存档撑大
        if (!isKnownPlayer(server, friends, targetId)) return false;

        if (!friends.addRequest(selfId, targetId, System.currentTimeMillis())) return false;
        rememberBoth(server, friends, selfId, targetId);
        return true;
    }

    /**
     * 处理一条收到的好友申请。
     *
     * 同意与拒绝共用一套校验：申请必须真的存在、且确实是发给我的。
     * 分成两个方法等于把校验抄两遍，改一处漏一处。
     *
     * @param accept true 同意并建立好友关系，false 仅拒绝
     * @return 这条申请是否真的存在并被处理
     */
    public static boolean respondFriendRequest(ServerPlayer self, UUID requesterId, boolean accept) {
        if (!PhoneItem.isCarriedBy(self)) return false;

        UUID selfId = self.getUUID();
        FriendData friends = FriendData.get(self.server);

        // 申请不存在就直接退出：拦住的是伪造客户端凭空"同意"一条不存在的申请，
        // 那等于单方面把任何人拉成自己的好友
        if (!friends.hasRequest(requesterId, selfId)) return false;

        friends.removeRequest(requesterId, selfId);

        if (accept) {
            // 同意的瞬间双方都可能已经加满，再查一次
            if (friends.countFriends(selfId) >= FriendData.MAX_FRIENDS) return true;
            if (friends.countFriends(requesterId) >= FriendData.MAX_FRIENDS) return true;

            friends.addFriendship(selfId, requesterId);
            rememberBoth(self.server, friends, selfId, requesterId);
        }
        return true;
    }

    /**
     * 解除好友关系。
     *
     * 双向解除，同时清掉本人的已读进度——不清的话日后重新加回来，
     * 未读数会从一个旧的时间点开始算，对不上。
     *
     * 聊天记录不删：那是双方共有的，单方面抹掉等于替对方做决定。
     */
    public static boolean removeFriend(ServerPlayer self, UUID targetId) {
        if (!PhoneItem.isCarriedBy(self)) return false;

        FriendData friends = FriendData.get(self.server);
        if (!friends.removeFriendship(self.getUUID(), targetId)) return false;

        ChatReadState read = self.getData(ModAttachments.CHAT_READ.get());
        ChatReadState updated = read.without(targetId);
        if (updated != read) {
            self.setData(ModAttachments.CHAT_READ.get(), updated);
        }
        return true;
    }

    // ============================================================
    //  在线玩家
    // ============================================================

    /**
     * 列出在线玩家，供"加联系人"界面使用。
     *
     * 排除本人。超过上限时截断——300 人的服务器全量下发既没意义
     * （手机屏幕也翻不完），又是白送的攻击面。总人数由调用方另行取得
     * 一并下发，界面能明确写出被截断了多少。
     *
     * 顺带把每个在线玩家的名字记进缓存：这是全服唯一能可靠拿到
     * "UUID 对应谁"的时机，他们离线后就只能靠这份缓存了。
     */
    public static List<OnlinePlayer> listOnlinePlayers(ServerPlayer self, int limit) {
        UUID selfId = self.getUUID();
        FriendData friends = FriendData.get(self.server);

        List<OnlinePlayer> out = new ArrayList<>();
        for (ServerPlayer p : self.server.getPlayerList().getPlayers()) {
            UUID id = p.getUUID();
            if (id.equals(selfId)) continue;

            String name = p.getGameProfile().getName();
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

    /** 本人与某人的关系 */
    public static Relation relationTo(FriendData friends, UUID selfId, UUID otherId) {
        if (friends.areFriends(selfId, otherId)) return Relation.FRIEND;
        if (friends.hasRequest(selfId, otherId)) return Relation.REQUEST_SENT;
        if (friends.hasRequest(otherId, selfId)) return Relation.REQUEST_RECEIVED;
        return Relation.NONE;
    }

    // ============================================================
    //  内部
    // ============================================================

    /** 服务端见过这个人吗 —— 在线，或名字缓存/资料缓存里有记录 */
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

    /**
     * 把 UUID 解析成显示名，按可靠性依次尝试：
     *
     *   1. 对方在线 —— 用当前真名，最准
     *   2. 好友数据里的名字缓存 —— 离线也认得出是谁，这正是存它的理由
     *   3. 服务端资料缓存 —— 缓存可能被清空，所以不能只靠它
     *   4. 都没有 —— 退回 UUID 前 8 位，至少不是空白
     *
     * 第 4 条不该出现，但宁可显示得难看，也不要让界面上出现空名字或抛异常。
     */
    public static String resolveName(MinecraftServer server, FriendData friends,
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
