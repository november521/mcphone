package com.november.mcphone.api.chat;

import com.november.mcphone.core.PhonePlayerData;
import com.november.mcphone.feature.chat.ChatData;
import com.november.mcphone.feature.chat.ChatDelivery;
import com.november.mcphone.feature.chat.ChatMessage;
import com.november.mcphone.feature.chat.ChatService;
import com.november.mcphone.feature.chat.FriendData;
import com.november.mcphone.feature.chat.FriendGuard;
import com.november.mcphone.feature.chat.TextBody;
import com.november.mcphone.util.TextSanitizer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 聊天的服务端入口。线程、权限与兼容的约定见 {@link com.november.mcphone.api.chat 包说明}。
 */
public final class PhoneChat {

    private PhoneChat() {}

    /** 一条私信的字数上限（按 char 数）。发超了 {@link #sendText} 返回 TEXT_TOO_LONG，不会替你截断 */
    public static int maxTextLength() {
        return TextBody.MAX_LENGTH;
    }

    /** 服务端见过的玩家：在线，或 MCphone 的名字缓存、原版资料缓存里有记录。不会去问 Mojang */
    public static Optional<PhoneContact> findPlayer(MinecraftServer server, UUID id) {
        Objects.requireNonNull(id, "id");
        requireServerThread(server);

        FriendData friends = FriendData.get(server);
        if (!ChatService.isKnownPlayer(server, friends, id)) return Optional.empty();
        return Optional.of(contact(server, friends, id));
    }

    /**
     * 按名字找，不分大小写。先找在线的。
     *
     * <p>不在线时查的是 MCphone 最后一次见到的名字：对上不止一个人、或原版资料缓存里这个 UUID 记着别的名字，
     * 都返回空。改了名却两边都没来得及更新的人仍可能被查成旧名字，拿结果决定钱给谁之前要让玩家确认。
     * 不按名字查原版资料缓存：它查不到时会去问 Mojang，卡住主线程。
     */
    public static Optional<PhoneContact> findPlayer(MinecraftServer server, String name) {
        Objects.requireNonNull(name, "name");
        requireServerThread(server);

        FriendData friends = FriendData.get(server);
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return Optional.of(contact(server, friends, online.getUUID()));

        List<UUID> cached = friends.idsNamed(name);
        if (cached.size() != 1) return Optional.empty();
        UUID id = cached.get(0);

        var cachedName = com.november.mcphone.platform.PlayerAccess.cachedName(server,id);
        if (cachedName.filter(p -> !p.equalsIgnoreCase(name)).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(contact(server, friends, id));
    }

    /** 这个玩家的好友，按名字排序。玩家不在线也能查 */
    public static List<PhoneContact> contacts(MinecraftServer server, UUID player) {
        Objects.requireNonNull(player, "player");
        requireServerThread(server);

        FriendData friends = FriendData.get(server);
        List<PhoneContact> out = new ArrayList<>();
        for (UUID peer : friends.getFriends(player)) {
            out.add(contact(server, friends, peer));
        }
        out.sort(Comparator.comparing(PhoneContact::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PhoneContact::id));
        return List.copyOf(out);
    }

    /** self 看 other 是什么关系 */
    public static ContactRelation relation(MinecraftServer server, UUID self, UUID other) {
        Objects.requireNonNull(self, "self");
        Objects.requireNonNull(other, "other");
        requireServerThread(server);

        if (self.equals(other)) return ContactRelation.SELF;
        FriendData friends = FriendData.get(server);
        if (friends.areFriends(self, other)) return ContactRelation.FRIEND;
        if (friends.hasRequest(self, other)) return ContactRelation.REQUEST_SENT;
        if (friends.hasRequest(other, self)) return ContactRelation.REQUEST_RECEIVED;
        return ContactRelation.NONE;
    }

    /**
     * 两人之间的私信会话。会话随好友关系存在，不需要也不能单独创建：是好友就有，不是就返回空。
     * 未读数按 self 的已读进度算，所以 self 得是此刻在线的那个实体；已下线的旧引用也返回空。
     */
    public static Optional<PhoneConversation> conversation(ServerPlayer self, UUID peer) {
        Objects.requireNonNull(peer, "peer");
        MinecraftServer server = self.level().getServer();
        requireServerThread(server);
        if (!isCurrent(self)) return Optional.empty();

        UUID selfId = self.getUUID();
        FriendData friends = FriendData.get(server);
        if (!friends.areFriends(selfId, peer)) return Optional.empty();

        long since = PhonePlayerData.of(self).chatRead().getLastRead(peer);
        // 附属读会话时也采用本人可见记录，与手机预览和未读保持一致。
        ChatData.Tail tail = ChatData.tail(ChatService.getMessages(self, peer), peer, since);
        OptionalLong last = tail.last() == null ? OptionalLong.empty() : OptionalLong.of(tail.last().time());
        return Optional.of(new PhoneConversation(selfId, contact(server, friends, peer), tail.unread(), last));
    }

    /**
     * 以 sender 的名义给 recipient 发一条文本私信：存进聊天记录，双方在线就立刻出现在界面上，
     * 收件人那边与收到好友亲手发的消息走同一套通知。
     *
     * <p>收件人看到的与 sender 亲手发的一模一样，分不出是附属代发的；sender 自己的聊天界面里也会出现这一条。
     * 不动 sender 的已读进度。
     *
     * <p>sender 必须是玩家列表里此刻登记的那个实体，否则返回 SENDER_OFFLINE（哪些情况见它的说明）。
     * § 格式符与控制字符会被去掉、首尾空白会被裁掉，剩下的超过 {@link #maxTextLength()} 就整条拒收。
     * 每对会话只留最近 100 条，发得多会把两人真正的聊天挤掉。
     */
    public static SendResult sendText(ServerPlayer sender, UUID recipient, String text) {
        Objects.requireNonNull(recipient, "recipient");
        MinecraftServer server = sender.level().getServer();
        requireServerThread(server);
        if (!isCurrent(sender)) return SendResult.SENDER_OFFLINE;

        UUID senderId = sender.getUUID();
        if (senderId.equals(recipient)) return SendResult.SELF;
        if (!FriendData.get(server).areFriends(senderId, recipient)) return SendResult.NOT_FRIENDS;
        if (!FriendGuard.carriesPhone(sender)) return SendResult.NO_PHONE;

        String clean = TextSanitizer.sanitize(text, Integer.MAX_VALUE);
        if (clean.isEmpty()) return SendResult.EMPTY_TEXT;
        if (clean.length() > TextBody.MAX_LENGTH) return SendResult.TEXT_TOO_LONG;

        ChatDelivery.requireInstalled();
        ChatMessage message = ChatService.storeText(sender, recipient, clean);
        return ChatDelivery.deliver(sender, recipient, message)
                ? SendResult.DELIVERED
                : SendResult.STORED_OFFLINE;
    }

    /** 下线或重生之后，附属手里那个旧实体的背包与已读进度都不再是这个玩家的，写进去会丢 */
    private static boolean isCurrent(ServerPlayer player) {
        return !player.hasDisconnected()
                && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player;
    }

    private static PhoneContact contact(MinecraftServer server, FriendData friends, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        return new PhoneContact(id, ChatService.resolveName(server, friends, id, online), online != null);
    }

    private static void requireServerThread(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException("[MCphone] PhoneChat 只能在服务端主线程调用，当前线程: "
                    + Thread.currentThread().getName());
        }
    }
}
