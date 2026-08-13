package com.november.mcphone.network.chat;

import com.november.mcphone.chat.ChatService;
import net.minecraft.server.level.ServerPlayer;
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

    // ============================================================
    //  客户端处理
    // ============================================================

    /** 收到会话列表，存进客户端缓存供界面读取 */
    private static void handleSyncConversations(SyncConversationsPacket packet,
                                                IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChatClientCache.setConversations(packet.conversations()));
    }
}
