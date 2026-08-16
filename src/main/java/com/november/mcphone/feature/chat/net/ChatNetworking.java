package com.november.mcphone.feature.chat.net;

import com.november.mcphone.feature.chat.ChatMessage;
import com.november.mcphone.feature.chat.ChatService;
import com.november.mcphone.feature.chat.FriendData;
import com.november.mcphone.feature.chat.FriendOutcome;
import com.november.mcphone.core.net.RequestThrottle;
import net.minecraft.network.chat.Component;
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

        // C2S: 会话开着时来了新消息，补一次已读
        registrar.playToServer(
                MarkReadPacket.TYPE,
                MarkReadPacket.STREAM_CODEC,
                ChatNetworking::handleMarkRead
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

        // C2S: 发出好友申请
        registrar.playToServer(
                FriendRequestPacket.TYPE,
                FriendRequestPacket.STREAM_CODEC,
                ChatNetworking::handleFriendRequest
        );

        // C2S: 同意或拒绝一条好友申请
        registrar.playToServer(
                RespondFriendRequestPacket.TYPE,
                RespondFriendRequestPacket.STREAM_CODEC,
                ChatNetworking::handleRespondFriendRequest
        );

        // C2S: 解除好友
        registrar.playToServer(
                RemoveFriendPacket.TYPE,
                RemoveFriendPacket.STREAM_CODEC,
                ChatNetworking::handleRemoveFriend
        );
    }

    // ============================================================
    //  服务端处理
    // ============================================================

    /**
     * 客户端请求会话列表。
     *
     * 不需要校验"身上有没有手机"：这只是读自己的数据，没有手机也看不到
     * 别人的东西，加检查只会在玩家边走边收消息时误伤。写操作（发消息、
     * 加好友）才需要那道检查。
     */
    private static void handleRequestConversations(RequestConversationsPacket packet,
                                                   IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!RequestThrottle.allow(player, RequestThrottle.Kind.CONVERSATIONS)) return;

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
            if (!RequestThrottle.allow(player, RequestThrottle.Kind.MESSAGES)) return;

            List<ChatMessage> messages = ChatService.getMessages(player, packet.peer());
            ChatService.markRead(player, packet.peer());
            ctx.reply(new SyncMessagesPacket(packet.peer(), messages));
        });
    }

    /**
     * 客户端报告"这个会话我看着呢"。
     *
     * 不回包：已读时刻只影响未读数，而未读数会随下一轮会话列表一起下发，
     * 单独回一条没人用。
     *
     * 不校验对方是不是好友：ChatService.markRead 写的是自己的已读进度，
     * 对着一个陌生人的 UUID 标已读，最坏也只是在自己的存档里留一条无用
     * 记录，构不成滥用。
     */
    private static void handleMarkRead(MarkReadPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!RequestThrottle.allow(player, RequestThrottle.Kind.MARK_READ)) return;
            ChatService.markRead(player, packet.peer());
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
            if (!RequestThrottle.allow(player, RequestThrottle.Kind.ONLINE_PLAYERS)) return;

            ctx.reply(buildOnlinePlayersPacket(player));
        });
    }

    /**
     * 发出好友申请。
     *
     * 成功与否都回发最新的两份列表：在线玩家列表决定按钮显示"添加"、
     * "已申请"还是"同意"，会话列表决定对方是否出现在首页。失败时
     * （超上限、对方不存在）回发的仍是真实状态，界面不会显示成功的假象。
     */
    private static void handleFriendRequest(FriendRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            tell(player, ChatService.sendFriendRequest(player, packet.target()));
            replyState(ctx, player);
        });
    }

    /** 同意或拒绝一条好友申请 */
    private static void handleRespondFriendRequest(RespondFriendRequestPacket packet,
                                                   IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            tell(player, ChatService.respondFriendRequest(
                    player, packet.requester(), packet.accept()));
            replyState(ctx, player);
        });
    }

    /**
     * 把"为什么没成"用动作栏告诉玩家。
     *
     * 界面上这几种失败全都长得一模一样：按钮闪一下，还是原样。玩家分不出
     * 是自己满了、对方满了、还是这个人服务端没见过，只会反复点，然后来报
     * "加好友是坏的"。一句话就能把他引向真正的原因。
     *
     * 走动作栏而不是聊天框：这是一次操作的即时反馈，不该在公屏历史里留一行。
     * 与印笔记的成功/失败提示是同一套做法。
     *
     * OK 与 NOTHING 都不说话。前者界面上自己看得见变化；后者要么是正常
     * 客户端走不到的路径（身上没手机、申请不存在），要么本来就是那个状态。
     */
    private static void tell(ServerPlayer player, FriendOutcome outcome) {
        String key = switch (outcome) {
            case SELF_FULL       -> "mcphone.chat.friend_self_full";
            case PEER_FULL       -> "mcphone.chat.friend_peer_full";
            case PEER_INBOX_FULL -> "mcphone.chat.friend_peer_inbox_full";
            case UNKNOWN_PLAYER  -> "mcphone.chat.friend_unknown_player";
            case OK, NOTHING     -> null;
        };
        if (key == null) return;

        player.displayClientMessage(
                Component.translatable(key, FriendData.MAX_FRIENDS), true);
    }

    /** 解除好友。同样回发两份列表让界面归位 */
    private static void handleRemoveFriend(RemoveFriendPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            ChatService.removeFriend(player, packet.target());
            replyState(ctx, player);
        });
    }

    /**
     * 关系变化后统一回发在线列表与会话列表。
     *
     * 三个入口都要发同样两份，抽出来免得漏发其中一份——漏了的话界面
     * 会停在旧状态，玩家以为操作没生效，重复点击。
     */
    private static void replyState(IPayloadContext ctx, ServerPlayer player) {
        ctx.reply(buildOnlinePlayersPacket(player));
        ctx.reply(new SyncConversationsPacket(ChatService.buildConversations(player)));
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
        ctx.enqueueWork(() -> ChatClientCache.onNewMessage(packet.peer(), packet.message()));
    }

    /** 收到在线玩家列表 */
    private static void handleSyncOnlinePlayers(SyncOnlinePlayersPacket packet,
                                                IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChatClientCache.setOnlinePlayers(
                packet.players(), packet.totalOnline()));
    }
}
