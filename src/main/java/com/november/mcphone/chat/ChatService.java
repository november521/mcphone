package com.november.mcphone.chat;

import com.november.mcphone.ModAttachments;
import com.november.mcphone.PhoneItem;
import com.november.mcphone.network.chat.ConversationSummary;
import com.november.mcphone.network.chat.OnlinePlayer;
import com.november.mcphone.util.TextSanitizer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.InteractionHand;

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
     * 发一条消息。所有规则集中在这里，网络层只管收包与回包。
     *
     * 校验逐条都有原因，少一条都能被伪造客户端利用：
     *
     *   - 手上得有手机：没有这条，改个客户端就能凭空聊天，
     *     "掏出手机"这个前提形同虚设
     *   - 不能发给自己：会话键归一化后 A|A 是合法键，不拦的话会产生
     *     一个自己跟自己的会话，界面上莫名其妙
     *   - 收件人必须是"认识的人"（联系人 / 在线 / 已有会话）：
     *     否则可以对着随便编造的 UUID 发消息，把存档撑满
     *   - 正文清洗后不能为空：纯空格或纯格式符的消息没有意义
     *
     * 时间戳由服务端盖章，不采信客户端——客户端的钟不可信，早一点晚一点
     * 会打乱会话排序，极端情况下能把自己的消息永远顶在列表最前。
     *
     * @return 落库后的消息；未通过校验时返回 null
     */
    public static ChatMessage sendMessage(ServerPlayer sender, UUID targetId, String rawText) {
        if (!isHoldingPhone(sender)) return null;

        UUID senderId = sender.getUUID();
        if (senderId.equals(targetId)) return null;

        String text = TextSanitizer.sanitize(rawText, ChatMessage.MAX_TEXT_LENGTH);
        if (text.isEmpty()) return null;

        MinecraftServer server = sender.server;
        ChatData chat = ChatData.get(server);
        if (!isKnownPeer(sender, chat, targetId)) return null;

        ChatMessage message = new ChatMessage(senderId, text, System.currentTimeMillis());
        chat.addMessage(senderId, targetId, message);

        // 自己发的消息对自己而言当然是已读的。不推进已读时刻的话，
        // 未读数虽已按发件人过滤（见 ChatData.countAfter），但对方回复前
        // 的这段时间里"上次已读"仍停在旧值，回复一到就会把中间的全算成未读
        ContactsData contacts = sender.getData(ModAttachments.CONTACTS.get());
        sender.setData(ModAttachments.CONTACTS.get(),
                contacts.withLastRead(targetId, message.time()));

        return message;
    }

    /** 取某个会话的历史消息 */
    public static List<ChatMessage> getMessages(ServerPlayer self, UUID peer) {
        return ChatData.get(self.server).getMessages(self.getUUID(), peer);
    }

    /**
     * 把与某人的会话标为已读到此刻。
     *
     * 已读时刻由服务端盖章而非采信客户端：客户端报一个未来的时间戳就能
     * 把之后收到的消息全标成已读，红点再也不出现。
     */
    public static void markRead(ServerPlayer self, UUID peer) {
        ContactsData contacts = self.getData(ModAttachments.CONTACTS.get());
        ContactsData updated = contacts.withLastRead(peer, System.currentTimeMillis());
        if (updated != contacts) {
            self.setData(ModAttachments.CONTACTS.get(), updated);
        }
    }

    /**
     * 列出在线玩家，供"加联系人"界面使用。
     *
     * 排除本人。超过上限时截断——300 人的服务器全量下发既没意义
     * （手机屏幕也翻不完），又是白送的攻击面。总人数由调用方另行取得
     * 一并下发，界面能明确写出被截断了多少，而不是让玩家以为就这些人。
     *
     * isContact 由服务端算好带下去：客户端自己比对也能算，但那要求它
     * 手里有完整联系人表，白白多同步一份数据。
     */
    public static List<OnlinePlayer> listOnlinePlayers(ServerPlayer self, int limit) {
        ContactsData contacts = self.getData(ModAttachments.CONTACTS.get());
        UUID selfId = self.getUUID();

        List<OnlinePlayer> out = new ArrayList<>();
        for (ServerPlayer p : self.server.getPlayerList().getPlayers()) {
            if (p.getUUID().equals(selfId)) continue;
            if (out.size() >= limit) break;

            out.add(new OnlinePlayer(p.getUUID(), p.getGameProfile().getName(),
                    contacts.hasContact(p.getUUID())));
        }
        return out;
    }

    /** 在线人数（不含本人），用于告知界面列表被截断了多少 */
    public static int countOnlineExcludingSelf(ServerPlayer self) {
        return Math.max(0, self.server.getPlayerList().getPlayerCount() - 1);
    }

    /**
     * 加为联系人。
     *
     * 名字由服务端自己解析，不采信客户端传来的——否则伪造客户端能把
     * 联系人存成任意字符串，那是往别人存档里写垃圾。
     *
     * 只有"认识的人"能加：在线的，或已经聊过的（陌生人给你发过消息，
     * 你要能把他加回来）。否则可以对着编造的 UUID 无限加好友。
     *
     * @return 真的加上了才返回 true。已存在、超上限、对象不合法都返回 false
     */
    public static boolean addContact(ServerPlayer self, UUID targetId) {
        if (!isHoldingPhone(self)) return false;
        if (self.getUUID().equals(targetId)) return false;

        ChatData chat = ChatData.get(self.server);
        if (!isKnownPeer(self, chat, targetId)) return false;

        ContactsData contacts = self.getData(ModAttachments.CONTACTS.get());
        String name = resolveName(self.server, contacts, targetId,
                self.server.getPlayerList().getPlayer(targetId));

        ContactsData updated = contacts.withContact(new Contact(targetId, name));
        if (updated == contacts) return false;   // 已存在或已达上限

        self.setData(ModAttachments.CONTACTS.get(), updated);
        return true;
    }

    /**
     * 删除联系人。
     *
     * 只删本人这一侧：对方的联系人列表是他自己的数据。聊天记录也不删，
     * 那是双方共有的，单方面抹掉等于替对方做决定。
     */
    public static boolean removeContact(ServerPlayer self, UUID targetId) {
        if (!isHoldingPhone(self)) return false;

        ContactsData contacts = self.getData(ModAttachments.CONTACTS.get());
        ContactsData updated = contacts.withoutContact(targetId);
        if (updated == contacts) return false;

        self.setData(ModAttachments.CONTACTS.get(), updated);
        return true;
    }

    /** 手上（任意一只手）拿着手机吗 */
    public static boolean isHoldingPhone(ServerPlayer player) {
        return player.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof PhoneItem
            || player.getItemInHand(InteractionHand.OFF_HAND).getItem() instanceof PhoneItem;
    }

    /**
     * 这个人是"认识的"吗 —— 联系人、当前在线、或已经聊过。
     *
     * 拦住的是对着随便编造的 UUID 发消息：那既能把存档撑满，
     * 又能给素未谋面的玩家凭空塞一条会话。
     */
    private static boolean isKnownPeer(ServerPlayer self, ChatData chat, UUID peer) {
        ContactsData contacts = self.getData(ModAttachments.CONTACTS.get());
        if (contacts.hasContact(peer)) return true;
        if (self.server.getPlayerList().getPlayer(peer) != null) return true;
        return chat.getLastMessage(self.getUUID(), peer) != null;
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
