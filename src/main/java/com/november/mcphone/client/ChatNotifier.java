package com.november.mcphone.client;

import com.november.mcphone.PhoneItem;
import com.november.mcphone.chat.ChatMessage;
import com.november.mcphone.gui.PhoneScreen;
import com.november.mcphone.gui.PhoneToast;
import com.november.mcphone.network.chat.ChatClientCache;
import com.november.mcphone.network.chat.ConversationSummary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.ToastComponent;

import java.util.UUID;

/**
 * 收到消息时在游戏里提醒玩家。
 *
 * 由 {@link ChatClientCache} 的消息回调驱动，客户端启动时装上
 * （见 MCphoneClient）。走回调而不是让网络层直接调这里，是因为网络层
 * 在专用服务器上也会加载，碰不得客户端的类。
 *
 * ============================================================
 * 三个不提醒的情形
 * ============================================================
 *
 * 自己发的：服务端会把自己发出的消息回声送回来，好让界面立刻显示。
 *   对这条弹通知等于自己提醒自己。
 *
 * 正看着这个会话：消息已经出现在眼前了，右上角再弹一个纯属打扰。
 *   看的是【别的】会话或手机的其他界面时照常提醒——通知画在界面之上，
 *   看得见。
 *
 * 手机不在身上：这是"带着手机才收得到提醒"的字面意思。消息本身照常
 *   落库，下次把手机拿在身上打开就能看到，不会丢。
 *
 * 检查放在客户端而不是服务端：客户端手上就有自己完整的背包，不必为此
 * 让服务端多扫一遍每个收件人的物品栏。消息推送本来也得发——界面缓存
 * 要用——省不掉。
 */
public final class ChatNotifier {

    private ChatNotifier() {}

    /** 收到一条消息（自己发的回声也会走到这里） */
    public static void onMessage(UUID peer, ChatMessage message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (message.sender().equals(mc.player.getUUID())) return;
        if (isViewing(peer)) return;
        if (!PhoneItem.isCarriedBy(mc.player)) return;

        show(mc, peer, message);
    }

    /** 手机正开着，而且开的就是与这个人的会话 */
    private static boolean isViewing(UUID peer) {
        return Minecraft.getInstance().screen instanceof PhoneScreen phone
                && phone.isViewingConversation(peer);
    }

    /**
     * 弹通知，同一个人已有一条就合并进去。
     *
     * 不合并的话，一个人连发五条就能占满原版通知区仅有的 5 个槽位，
     * 把别的通知全挤到后面排队。
     */
    private static void show(Minecraft mc, UUID peer, ChatMessage message) {
        ToastComponent toasts = mc.getToasts();

        PhoneToast existing = toasts.getToast(PhoneToast.class, peer);
        if (existing != null) {
            existing.addMessage(PhoneToast.preview(message));
            return;
        }

        toasts.addToast(new PhoneToast(peer, resolveName(peer), PhoneToast.preview(message)));
    }

    /**
     * 取发信人的显示名。
     *
     * 会话列表快照里就有——对方既然能给我发消息，就一定是好友，一定在
     * 那份列表里。取不到只可能是消息比列表先到（比如刚成为好友的第一句），
     * 那就先显示 UUID 前 8 位，下次刷新就正常了。
     */
    private static String resolveName(UUID peer) {
        for (ConversationSummary c : ChatClientCache.getConversations()) {
            if (c.id().equals(peer)) return c.name();
        }
        return PhoneToast.fallbackName(peer);
    }
}
