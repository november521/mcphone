package com.november.mcphone.feature.chat.net;

import com.november.mcphone.feature.chat.ChatMessage;
import com.november.mcphone.feature.chat.ChatService;
import com.november.mcphone.feature.chat.ChatOutcome;
import com.november.mcphone.feature.chat.TeleportService;
import com.november.mcphone.core.net.RequestThrottle;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;

/** 聊天相关网络包的注册与处理，只做传输层的事；业务规则在 {@link ChatService}。 */
public final class ChatNetworking {

    private ChatNetworking() {}

    /** 由 NetworkHandler.register 调用 */
    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(
                RequestConversationsPacket.TYPE,
                RequestConversationsPacket.STREAM_CODEC,
                ChatNetworking::handleRequestConversations
        );

        registrar.playToClient(
                SyncConversationsPacket.TYPE,
                SyncConversationsPacket.STREAM_CODEC,
                ChatNetworking::handleSyncConversations
        );

        registrar.playToServer(
                RequestMessagesPacket.TYPE,
                RequestMessagesPacket.STREAM_CODEC,
                ChatNetworking::handleRequestMessages
        );

        registrar.playToClient(
                SyncMessagesPacket.TYPE,
                SyncMessagesPacket.STREAM_CODEC,
                ChatNetworking::handleSyncMessages
        );

        registrar.playToServer(
                MarkReadPacket.TYPE,
                MarkReadPacket.STREAM_CODEC,
                ChatNetworking::handleMarkRead
        );

        registrar.playToServer(
                SendChatMessagePacket.TYPE,
                SendChatMessagePacket.STREAM_CODEC,
                ChatNetworking::handleSendMessage
        );

        registrar.playToClient(
                NewMessagePacket.TYPE,
                NewMessagePacket.STREAM_CODEC,
                ChatNetworking::handleNewMessage
        );

        registrar.playToServer(
                RequestOnlinePlayersPacket.TYPE,
                RequestOnlinePlayersPacket.STREAM_CODEC,
                ChatNetworking::handleRequestOnlinePlayers
        );

        registrar.playToClient(
                SyncOnlinePlayersPacket.TYPE,
                SyncOnlinePlayersPacket.STREAM_CODEC,
                ChatNetworking::handleSyncOnlinePlayers
        );

        registrar.playToServer(
                FriendRequestPacket.TYPE,
                FriendRequestPacket.STREAM_CODEC,
                ChatNetworking::handleFriendRequest
        );

        registrar.playToServer(
                RespondFriendRequestPacket.TYPE,
                RespondFriendRequestPacket.STREAM_CODEC,
                ChatNetworking::handleRespondFriendRequest
        );

        registrar.playToServer(
                RemoveFriendPacket.TYPE,
                RemoveFriendPacket.STREAM_CODEC,
                ChatNetworking::handleRemoveFriend
        );

        registrar.playToServer(
                TeleportToFriendPacket.TYPE,
                TeleportToFriendPacket.STREAM_CODEC,
                ChatNetworking::handleTeleportToFriend
        );
    }

    /** 读操作故意不校验手机：只是读自己的数据，加检查只会在玩家边走边收消息时误伤 */
    private static void handleRequestConversations(RequestConversationsPacket packet,
                                                   IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!RequestThrottle.allow(player, RequestThrottle.Kind.CONVERSATIONS)) return;

            List<ConversationSummary> conversations = ChatService.buildConversations(player);
            ctx.reply(new SyncConversationsPacket(conversations));
        });
    }

    /** 顺带标已读：玩家看到了，未读数就该清零 */
    private static void handleRequestMessages(RequestMessagesPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!RequestThrottle.allow(player, RequestThrottle.Kind.MESSAGES)) return;

            List<ChatMessage> messages = ChatService.getMessages(player, packet.peer());
            ChatService.markRead(player, packet.peer());
            ctx.reply(new SyncMessagesPacket(packet.peer(), messages));
        });
    }

    /** 不回包：未读数随下一轮会话列表下发。不校验好友：写的只是自己的已读进度，构不成滥用 */
    private static void handleMarkRead(MarkReadPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!RequestThrottle.allow(player, RequestThrottle.Kind.MARK_READ)) return;
            ChatService.markRead(player, packet.peer());
        });
    }

    /**
     * 校验没过时静默丢弃：能触发的只有伪造客户端。
     * 发件人也要收到回声才显示自己那条——客户端不做乐观插入，免得被丢弃的消息留在界面上。
     */
    private static void handleSendMessage(SendChatMessagePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sender)) return;

            ChatMessage message = ChatService.sendMessage(sender, packet.target(), packet.text());
            if (message == null) return;

            // 回声给发件人：站在他的角度，对端是收件人
            ctx.reply(new NewMessagePacket(packet.target(), message));

            // 收件人在线才推送；离线的话消息已落库，上线拉列表时会看到
            ServerPlayer receiver = sender.server.getPlayerList().getPlayer(packet.target());
            if (receiver != null) {
                PacketDistributor.sendToPlayer(receiver,
                        new NewMessagePacket(sender.getUUID(), message));
            }
        });
    }

    private static void handleRequestOnlinePlayers(RequestOnlinePlayersPacket packet,
                                                   IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!RequestThrottle.allow(player, RequestThrottle.Kind.ONLINE_PLAYERS)) return;

            ctx.reply(buildOnlinePlayersPacket(player));
        });
    }

    /** 成功与否都回发最新状态，失败时界面不会显示成功的假象 */
    private static void handleFriendRequest(FriendRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            tell(player, ChatService.sendFriendRequest(player, packet.target()));
            replyState(ctx, player);
        });
    }

    private static void handleRespondFriendRequest(RespondFriendRequestPacket packet,
                                                   IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            tell(player, ChatService.respondFriendRequest(
                    player, packet.requester(), packet.accept()));
            replyState(ctx, player);
        });
    }

    /** 把"为什么没成"发到动作栏；说什么由结果自己决定（见 {@link ChatOutcome}），这里只管送达 */
    private static void tell(ServerPlayer player, ChatOutcome outcome) {
        Component message = outcome.message();
        if (message == null) return;

        player.displayClientMessage(message, true);
    }

    private static void handleRemoveFriend(RemoveFriendPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            ChatService.removeFriend(player, packet.target());
            replyState(ctx, player);
        });
    }

    /** 不回发列表：客户端点下按钮的同一帧就关机了，没有界面会读它 */
    private static void handleTeleportToFriend(TeleportToFriendPacket packet,
                                               IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!RequestThrottle.allow(player, RequestThrottle.Kind.TELEPORT)) return;

            tell(player, TeleportService.teleportToFriend(player, packet.target()));
        });
    }

    /** 关系变化后统一回发两份列表，抽出来免得漏发其中一份 */
    private static void replyState(IPayloadContext ctx, ServerPlayer player) {
        ctx.reply(buildOnlinePlayersPacket(player));
        ctx.reply(new SyncConversationsPacket(ChatService.buildConversations(player)));
    }

    /** 截断到上限，并带上真实总数供界面提示 */
    private static SyncOnlinePlayersPacket buildOnlinePlayersPacket(ServerPlayer player) {
        return new SyncOnlinePlayersPacket(
                ChatService.listOnlinePlayers(player, SyncOnlinePlayersPacket.MAX_PLAYERS),
                ChatService.countOnlineExcludingSelf(player));
    }

    private static void handleSyncConversations(SyncConversationsPacket packet,
                                                IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChatClientCache.setConversations(packet.conversations()));
    }

    private static void handleSyncMessages(SyncMessagesPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChatClientCache.setMessages(packet.peer(), packet.messages()));
    }

    private static void handleNewMessage(NewMessagePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChatClientCache.onNewMessage(packet.peer(), packet.message()));
    }

    private static void handleSyncOnlinePlayers(SyncOnlinePlayersPacket packet,
                                                IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChatClientCache.setOnlinePlayers(
                packet.players(), packet.totalOnline()));
    }
}
