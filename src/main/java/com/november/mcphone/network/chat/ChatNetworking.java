package com.november.mcphone.network.chat;

import com.november.mcphone.chat.ChatMessage;
import com.november.mcphone.chat.ChatService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;

/**
 * 聊天相关网络包的注册与处理。
 *
 * 从 NetworkHandler 拆出来是为了维护性：那边已有壁纸、设备名、末影箱
 * 三组包，聊天还要再加好几个，全塞一处会变成杂物间。NetworkHandler
 * 保留"注册总入口"这一个职责，调一行委托到这里。
 *
 * 本类只做传输层的事——收包、校验来源、把结果发回去。真正的业务规则
 * 在 {@link ChatService}。
 */
public final class ChatNetworking {

    private ChatNetworking() {}

    /** 由 NetworkHandler.register 调用 */
    public static void register(PayloadRegistrar registrar) {
        // C2S: 打开聊天 App，请求会话列表
        registrar.playToServer(
                RequestConversationsPacket.TYPE,
                RequestConversationsPacket.STREAM_CODEC,
                ChatNetworking::handleRequestConversations
        );

        // S2C: 下发会话列表
        registrar.playToClient(
                SyncConversationsPacket.TYPE,
                SyncConversationsPacket.STREAM_CODEC,
                ChatNetworking::handleSyncConversations
        );

        // C2S: 点进某个会话，请求历史消息
        registrar.playToServer(
                RequestMessagesPacket.TYPE,
                RequestMessagesPacket.STREAM_CODEC,
                ChatNetworking::handleRequestMessages
        );

        // S2C: 下发某个会话的历史消息
        registrar.playToClient(
                SyncMessagesPacket.TYPE,
                SyncMessagesPacket.STREAM_CODEC,
                ChatNetworking::handleSyncMessages
        );

        // C2S: 发一条消息
        registrar.playToServer(
                SendChatMessagePacket.TYPE,
                SendChatMessagePacket.STREAM_CODEC,
                ChatNetworking::handleSendMessage
        );

        // S2C: 来了一条新消息（收发双方都收）
        registrar.playToClient(
                NewMessagePacket.TYPE,
                NewMessagePacket.STREAM_CODEC,
                ChatNetworking::handleNewMessage
        );

        // C2S: 打开"加联系人"界面，请求在线玩家
        registrar.playToServer(
                RequestOnlinePlayersPacket.TYPE,
                RequestOnlinePlayersPacket.STREAM_CODEC,
                ChatNetworking::handleRequestOnlinePlayers
        );

        // S2C: 下发在线玩家列表
        registrar.playToClient(
                SyncOnlinePlayersPacket.TYPE,
                SyncOnlinePlayersPacket.STREAM_CODEC,
                ChatNetworking::handleSyncOnlinePlayers
        );

        // C2S: 加联系人
        registrar.playToServer(
                AddContactPacket.TYPE,
                AddContactPacket.STREAM_CODEC,
                ChatNetworking::handleAddContact
        );

        // C2S: 删联系人
        registrar.playToServer(
                RemoveContactPacket.TYPE,
                RemoveContactPacket.STREAM_CODEC,
                ChatNetworking::handleRemoveContact
        );
    }

    // ============================================================
    //  服务端处理
    // ============================================================

    /**
     * 客户端请求会话列表。
     *
     * 不需要校验"手上有没有手机"：这只是读自己的数据，没有手机也看不到
     * 别人的东西，加检查只会在玩家边走边收消息时误伤。写操作（发消息、
     * 加好友）才需要那道检查。
     */
    private static void handleRequestConversations(RequestConversationsPacket packet,
                                                   IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            List<ConversationSummary> conversations = ChatService.buildConversations(player);
            ctx.reply(new SyncConversationsPacket(conversations));
        });
    }

    /**
     * 客户端点进某个会话，请求历史消息。
     *
     * 顺带把这个会话标为已读——玩家看到了，未读数就该清零。已读时刻由
     * 服务端盖章，不采信客户端：客户端报一个未来的时间戳就能把之后收到的
     * 消息全标成已读，红点再也不出现。
     */
    private static void handleRequestMessages(RequestMessagesPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            List<ChatMessage> messages = ChatService.getMessages(player, packet.peer());
            ChatService.markRead(player, packet.peer());
            ctx.reply(new SyncMessagesPacket(packet.peer(), messages));
        });
    }

    /**
     * 客户端要发消息。
     *
     * 校验规则全在 ChatService，这里只负责把结果送出去。校验没过时静默
     * 丢弃：能触发的只有伪造客户端，给它回一条错误提示既没意义，
     * 又白白告诉对方哪条规则拦住了它。
     *
     * 收发双方都推：发件人也要收到回声，界面才能立刻显示自己发出去的那条。
     * 让客户端乐观插入的话，一旦服务端因校验不过丢弃了消息，
     * 界面上就留下一条并不存在的消息。
     */
    private static void handleSendMessage(SendChatMessagePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sender)) return;

            ChatMessage message = ChatService.sendMessage(sender, packet.target(), packet.text());
            if (message == null) return;

            // 回声给发件人：站在他的角度，对端是收件人
            ctx.reply(new NewMessagePacket(packet.target(), message));

            // 收件人在线才推送；离线的话消息已经落库，他上线拉会话列表时会看到
            ServerPlayer receiver = sender.server.getPlayerList().getPlayer(packet.target());
            if (receiver != null) {
                // 站在收件人的角度，对端是发件人
                PacketDistributor.sendToPlayer(receiver,
                        new NewMessagePacket(sender.getUUID(), message));
            }
        });
    }

    /** 客户端请求在线玩家列表。与拉会话列表同理，读操作不校验手机 */
    private static void handleRequestOnlinePlayers(RequestOnlinePlayersPacket packet,
                                                   IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            ctx.reply(buildOnlinePlayersPacket(player));
        });
    }

    /**
     * 加联系人。
     *
     * 成功与否都回发最新的两份列表：在线玩家列表决定按钮显示"加为联系人"
     * 还是"已添加"，会话列表决定新联系人是否出现在首页。加失败时
     * （已存在、超上限）回发的仍是真实状态，界面不会显示成功的假象。
     */
    private static void handleAddContact(AddContactPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            ChatService.addContact(player, packet.target());
            ctx.reply(buildOnlinePlayersPacket(player));
            ctx.reply(new SyncConversationsPacket(ChatService.buildConversations(player)));
        });
    }

    /** 删联系人。同样回发两份列表让界面归位 */
    private static void handleRemoveContact(RemoveContactPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            ChatService.removeContact(player, packet.target());
            ctx.reply(buildOnlinePlayersPacket(player));
            ctx.reply(new SyncConversationsPacket(ChatService.buildConversations(player)));
        });
    }

    /** 组装在线玩家包：截断到上限，并带上真实总数供界面提示 */
    private static SyncOnlinePlayersPacket buildOnlinePlayersPacket(ServerPlayer player) {
        return new SyncOnlinePlayersPacket(
                ChatService.listOnlinePlayers(player, SyncOnlinePlayersPacket.MAX_PLAYERS),
                ChatService.countOnlineExcludingSelf(player));
    }

    // ============================================================
    //  客户端处理
    // ============================================================

    /** 收到会话列表，存进客户端缓存供界面读取 */
    private static void handleSyncConversations(SyncConversationsPacket packet,
                                                IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChatClientCache.setConversations(packet.conversations()));
    }

    /** 收到某个会话的历史消息 */
    private static void handleSyncMessages(SyncMessagesPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChatClientCache.setMessages(packet.peer(), packet.messages()));
    }

    /** 收到一条新消息 */
    private static void handleNewMessage(NewMessagePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChatClientCache.appendMessage(packet.peer(), packet.message()));
    }

    /** 收到在线玩家列表 */
    private static void handleSyncOnlinePlayers(SyncOnlinePlayersPacket packet,
                                                IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChatClientCache.setOnlinePlayers(
                packet.players(), packet.totalOnline()));
    }
}
