package com.november.mcphone.feature.chat.net;

import com.november.mcphone.feature.chat.ChatImageStore;
import com.november.mcphone.feature.chat.ChatImageUploads;
import com.november.mcphone.feature.chat.ChatMessage;
import com.november.mcphone.feature.chat.ChatService;
import com.november.mcphone.feature.chat.ChatOutcome;
import com.november.mcphone.feature.chat.ImageOutcome;
import com.november.mcphone.feature.chat.TeleportService;
import com.november.mcphone.core.net.RequestThrottle;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
                SendChatImagePacket.TYPE,
                SendChatImagePacket.STREAM_CODEC,
                ChatNetworking::handleSendImage
        );

        registrar.playToServer(
                RequestChatImagePacket.TYPE,
                RequestChatImagePacket.STREAM_CODEC,
                ChatNetworking::handleRequestImage
        );

        registrar.playToClient(
                ChatImageDataPacket.TYPE,
                ChatImageDataPacket.STREAM_CODEC,
                ChatNetworking::handleImageData
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

    /**
     * 收一张图的一片。拼齐了才有事发生，见 {@link ChatImageUploads}。
     *
     * 门禁与限流都只在第一片上做：中间几片被拦掉的话这次上传横竖也拼不齐，而每一片都
     * 查一遍好友关系、每一片都占一次限流额度，等于把一次正常的发图判成"发得太快"。
     */
    private static void handleSendImage(SendChatImagePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sender)) return;

            if (packet.chunkIndex() == 0) {
                ImageOutcome gate = ChatService.maySendImage(sender, packet.target());
                if (gate != ImageOutcome.OK) {
                    tell(sender, gate);
                    return;
                }
                if (!RequestThrottle.allow(sender, RequestThrottle.Kind.CHAT_IMAGE)) {
                    tell(sender, ImageOutcome.TOO_FAST);
                    return;
                }
            }

            ChatImageUploads.Assembled upload = ChatImageUploads.accept(
                    sender, packet.target(), packet.width(), packet.height(),
                    packet.chunkIndex(), packet.chunkCount(), packet.chunk());
            if (upload == null) return;

            // 服务端不解码图片，但也不能一个字节都不看就转发给别人的解码器
            if (!ChatImageStore.looksLikePng(upload.png())) {
                tell(sender, ImageOutcome.BROKEN);
                return;
            }

            storeAndDeliver(sender, upload);
        });
    }

    /**
     * 写盘在后台线程，落消息回到主线程。
     *
     * 写一张几十 KB 的文件在空闲的机器上是零点几毫秒，在一台正忙着的服务器上不是——
     * 而这条路是玩家点一下就走一遍的。主线程卡一下，全服都看得见。
     */
    private static void storeAndDeliver(ServerPlayer sender, ChatImageUploads.Assembled upload) {
        MinecraftServer server = sender.server;

        Util.backgroundExecutor().execute(() -> {
            UUID imageId = ChatImageStore.write(server, upload.png());

            server.execute(() -> {
                if (imageId == null) {
                    tell(sender, ImageOutcome.STORE_FAILED);
                    return;
                }

                ChatMessage message = ChatService.sendImage(sender, upload.target(),
                        imageId, upload.width(), upload.height());
                if (message == null) {
                    // 写盘这一会儿工夫里关系变了（解除好友、手机丢了）：把刚落地的那张图收回去，
                    // 否则它就是一张永远没有消息认领的孤儿
                    ChatImageStore.delete(server, imageId);
                    return;
                }

                // 与文本消息同一条路：发件人也靠回声显示自己那条。
                // 写盘期间他可能已经退出去了，那就只落消息不回声——下次上线拉历史照样看得见
                if (!sender.hasDisconnected()) {
                    PacketDistributor.sendToPlayer(sender,
                            new NewMessagePacket(upload.target(), message));
                }

                ServerPlayer receiver = server.getPlayerList().getPlayer(upload.target());
                if (receiver != null) {
                    PacketDistributor.sendToPlayer(receiver,
                            new NewMessagePacket(sender.getUUID(), message));
                }
            });
        });
    }

    /**
     * 把要的那几张图发回去。校验在主线程（要翻聊天记录），读盘挪到后台，发包再回主线程。
     *
     * 要不到的（不是好友、这张图不在你们的记录里）静默丢弃：正常客户端只会问它自己
     * 刚收到的那些 id，问了别的说明客户端被改过，回一句只是帮它试探。
     */
    private static void handleRequestImage(RequestChatImagePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!RequestThrottle.allow(player, RequestThrottle.Kind.CHAT_IMAGE_DATA)) return;

            List<UUID> allowed = new ArrayList<>();
            for (UUID id : packet.images()) {
                if (ChatService.mayReadImage(player, packet.peer(), id)) allowed.add(id);
            }
            if (allowed.isEmpty()) return;

            MinecraftServer server = player.server;
            Util.backgroundExecutor().execute(() -> {
                for (UUID id : allowed) {
                    byte[] data = ChatImageStore.read(server, id);
                    ChatImageDataPacket reply = data == null
                            ? ChatImageDataPacket.gone(id)
                            : new ChatImageDataPacket(id, data);
                    server.execute(() -> {
                        // 读盘期间他可能已经下线，发给一条死连接没有意义
                        if (!player.hasDisconnected()) PacketDistributor.sendToPlayer(player, reply);
                    });
                }
            });
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

    private static void handleImageData(ChatImageDataPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChatClientCache.onImageData(packet.image(), packet.data()));
    }

    private static void handleSyncOnlinePlayers(SyncOnlinePlayersPacket packet,
                                                IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChatClientCache.setOnlinePlayers(
                packet.players(), packet.totalOnline()));
    }
}
