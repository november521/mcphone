package com.november.mcphone.feature.chat.net;

import com.november.mcphone.feature.chat.ChatMessage;
import com.november.mcphone.feature.chat.ChatService;
import com.november.mcphone.feature.chat.ChatOutcome;
import com.november.mcphone.feature.chat.TeleportService;
import com.november.mcphone.core.net.RequestThrottle;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.november.mcphone.core.net.MCphoneNetwork;

import java.util.List;

/** 聊天相关网络包的注册与处理，只做传输层的事；业务规则在 {@link ChatService}。 */
public final class ChatNetworking {

    private ChatNetworking() {}

    /** 由 NetworkHandler.register 调用 */
    public static void register() {
        MCphoneNetwork.registerToServer(
                RequestConversationsPacket.class,
                RequestConversationsPacket::encode,
                RequestConversationsPacket::decode,
                ChatNetworking::handleRequestConversations
        );

        MCphoneNetwork.registerToClient(
                SyncConversationsPacket.class,
                SyncConversationsPacket::encode,
                SyncConversationsPacket::decode,
                ChatNetworking::handleSyncConversations
        );

        MCphoneNetwork.registerToServer(
                RequestMessagesPacket.class,
                RequestMessagesPacket::encode,
                RequestMessagesPacket::decode,
                ChatNetworking::handleRequestMessages
        );

        MCphoneNetwork.registerToClient(
                SyncMessagesPacket.class,
                SyncMessagesPacket::encode,
                SyncMessagesPacket::decode,
                ChatNetworking::handleSyncMessages
        );

        MCphoneNetwork.registerToServer(
                MarkReadPacket.class,
                MarkReadPacket::encode,
                MarkReadPacket::decode,
                ChatNetworking::handleMarkRead
        );

        MCphoneNetwork.registerToServer(
                SendChatMessagePacket.class,
                SendChatMessagePacket::encode,
                SendChatMessagePacket::decode,
                ChatNetworking::handleSendMessage
        );

        MCphoneNetwork.registerToClient(
                NewMessagePacket.class,
                NewMessagePacket::encode,
                NewMessagePacket::decode,
                ChatNetworking::handleNewMessage
        );

        MCphoneNetwork.registerToServer(
                RequestOnlinePlayersPacket.class,
                RequestOnlinePlayersPacket::encode,
                RequestOnlinePlayersPacket::decode,
                ChatNetworking::handleRequestOnlinePlayers
        );

        MCphoneNetwork.registerToClient(
                SyncOnlinePlayersPacket.class,
                SyncOnlinePlayersPacket::encode,
                SyncOnlinePlayersPacket::decode,
                ChatNetworking::handleSyncOnlinePlayers
        );

        MCphoneNetwork.registerToServer(
                FriendRequestPacket.class,
                FriendRequestPacket::encode,
                FriendRequestPacket::decode,
                ChatNetworking::handleFriendRequest
        );

        MCphoneNetwork.registerToServer(
                RespondFriendRequestPacket.class,
                RespondFriendRequestPacket::encode,
                RespondFriendRequestPacket::decode,
                ChatNetworking::handleRespondFriendRequest
        );

        MCphoneNetwork.registerToServer(
                RemoveFriendPacket.class,
                RemoveFriendPacket::encode,
                RemoveFriendPacket::decode,
                ChatNetworking::handleRemoveFriend
        );

        MCphoneNetwork.registerToServer(
                TeleportToFriendPacket.class,
                TeleportToFriendPacket::encode,
                TeleportToFriendPacket::decode,
                ChatNetworking::handleTeleportToFriend
        );
    }

    /** 读操作故意不校验手机：只是读自己的数据，加检查只会在玩家边走边收消息时误伤 */
    private static void handleRequestConversations(RequestConversationsPacket packet, ServerPlayer player) {
        if (!RequestThrottle.allow(player, RequestThrottle.Kind.CONVERSATIONS)) return;

        List<ConversationSummary> conversations = ChatService.buildConversations(player);
        MCphoneNetwork.sendToPlayer(player, new SyncConversationsPacket(conversations));
    }

    /** 顺带标已读：玩家看到了，未读数就该清零 */
    private static void handleRequestMessages(RequestMessagesPacket packet, ServerPlayer player) {
        if (!RequestThrottle.allow(player, RequestThrottle.Kind.MESSAGES)) return;

        List<ChatMessage> messages = ChatService.getMessages(player, packet.peer());
        ChatService.markRead(player, packet.peer());
        MCphoneNetwork.sendToPlayer(player, new SyncMessagesPacket(packet.peer(), messages));
    }

    /** 不回包：未读数随下一轮会话列表下发。不校验好友：写的只是自己的已读进度，构不成滥用 */
    private static void handleMarkRead(MarkReadPacket packet, ServerPlayer player) {
        if (!RequestThrottle.allow(player, RequestThrottle.Kind.MARK_READ)) return;
        ChatService.markRead(player, packet.peer());
    }

    /**
     * 校验没过时静默丢弃：能触发的只有伪造客户端。
     * 发件人也要收到回声才显示自己那条——客户端不做乐观插入，免得被丢弃的消息留在界面上。
     */
    private static void handleSendMessage(SendChatMessagePacket packet, ServerPlayer player) {
        ServerPlayer sender = player;

        ChatMessage message = ChatService.sendMessage(sender, packet.target(), packet.text());
        if (message == null) return;

        // 回声给发件人：站在他的角度，对端是收件人
        MCphoneNetwork.sendToPlayer(player, new NewMessagePacket(packet.target(), message));

        // 收件人在线才推送；离线的话消息已落库，上线拉列表时会看到
        ServerPlayer receiver = sender.server.getPlayerList().getPlayer(packet.target());
        if (receiver != null) {
            MCphoneNetwork.sendToPlayer(receiver,
                    new NewMessagePacket(sender.getUUID(), message));
        }
    }

    private static void handleRequestOnlinePlayers(RequestOnlinePlayersPacket packet, ServerPlayer player) {
        if (!RequestThrottle.allow(player, RequestThrottle.Kind.ONLINE_PLAYERS)) return;

        MCphoneNetwork.sendToPlayer(player, buildOnlinePlayersPacket(player));
    }

    /** 成功与否都回发最新状态，失败时界面不会显示成功的假象 */
    private static void handleFriendRequest(FriendRequestPacket packet, ServerPlayer player) {
        tell(player, ChatService.sendFriendRequest(player, packet.target()));
        replyState(player);
    }

    private static void handleRespondFriendRequest(RespondFriendRequestPacket packet, ServerPlayer player) {
        tell(player, ChatService.respondFriendRequest(
                player, packet.requester(), packet.accept()));
        replyState(player);
    }

    /** 把"为什么没成"发到动作栏；说什么由结果自己决定（见 {@link ChatOutcome}），这里只管送达 */
    private static void tell(ServerPlayer player, ChatOutcome outcome) {
        Component message = outcome.message();
        if (message == null) return;

        player.displayClientMessage(message, true);
    }

    private static void handleRemoveFriend(RemoveFriendPacket packet, ServerPlayer player) {
        ChatService.removeFriend(player, packet.target());
        replyState(player);
    }

    /** 不回发列表：客户端点下按钮的同一帧就关机了，没有界面会读它 */
    private static void handleTeleportToFriend(TeleportToFriendPacket packet, ServerPlayer player) {
        if (!RequestThrottle.allow(player, RequestThrottle.Kind.TELEPORT)) return;

        tell(player, TeleportService.teleportToFriend(player, packet.target()));
    }

    /** 关系变化后统一回发两份列表，抽出来免得漏发其中一份 */
    private static void replyState(ServerPlayer player) {
        MCphoneNetwork.sendToPlayer(player, buildOnlinePlayersPacket(player));
        MCphoneNetwork.sendToPlayer(player,
                new SyncConversationsPacket(ChatService.buildConversations(player)));
    }

    /** 截断到上限，并带上真实总数供界面提示 */
    private static SyncOnlinePlayersPacket buildOnlinePlayersPacket(ServerPlayer player) {
        return new SyncOnlinePlayersPacket(
                ChatService.listOnlinePlayers(player, SyncOnlinePlayersPacket.MAX_PLAYERS),
                ChatService.countOnlineExcludingSelf(player));
    }

    private static void handleSyncConversations(SyncConversationsPacket packet) {
        ChatClientCache.setConversations(packet.conversations());
    }

    private static void handleSyncMessages(SyncMessagesPacket packet) {
        ChatClientCache.setMessages(packet.peer(), packet.messages());
    }

    private static void handleNewMessage(NewMessagePacket packet) {
        ChatClientCache.onNewMessage(packet.peer(), packet.message());
    }

    private static void handleSyncOnlinePlayers(SyncOnlinePlayersPacket packet) {
        ChatClientCache.setOnlinePlayers(
                packet.players(), packet.totalOnline());
    }
}
