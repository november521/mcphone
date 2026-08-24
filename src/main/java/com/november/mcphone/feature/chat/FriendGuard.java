package com.november.mcphone.feature.chat;

import com.november.mcphone.core.PhoneItem;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 对好友动手之前的那几道门。
 *
 * 为什么单独摆出来
 *
 * 「手机得在身上」这一条现在写在五个方法里（发消息、发申请、答复申请、
 * 解除好友、传送），「对方得是好友」写在三个（发消息、拉历史、传送）。
 * 抄一遍不算问题，问题是它们是【安全边界】而不是风格：漏掉手机那一行，
 * "得有一部手机才能用"这个前提当场形同虚设，改个客户端就能凭空聊天、
 * 凭空传送——而漏掉了不会有任何症状，功能照常工作。
 *
 * 你说过美西螈以后还要加功能。每一个"对某个好友做的事"都要过同样两道门，
 * 摆在这里，下一个功能就是一行调用，而不是"记得抄那两行"。
 *
 * 为什么不把"对方在线"也并进来
 *
 * 因为调用方需要【分得出】是哪一种不行：不是好友要静默丢弃（只有伪造
 * 客户端能走到），而对方刚下线要说一句话（正常玩家撞得上，列表 3 秒
 * 才刷一次）。合成一个"返回 null 就是不行"的方法，这个区别就没了。
 *
 * 读操作不在这里
 *
 * 拉会话列表、拉历史消息这类只读自己数据的请求【不】校验手机——加检查
 * 只会在玩家边走边收消息时误伤，而没有手机也看不到别人的东西。
 * 这条取舍在 ChatNetworking 里已经写明，本类只管写操作。
 */
public final class FriendGuard {

    private FriendGuard() {}

    /**
     * 能不能对这个人动手：手机真在身上，且他确实是我的好友。
     *
     * 手机在主手、副手、背包、饰品槽都算，见 {@link PhoneItem#isCarriedBy}。
     *
     * 好友这一条同时堵掉了"对着随便编造的 UUID 操作"——否则只要猜到一个
     * UUID 就能给素未谋面的人塞会话、或者落到他家里。
     */
    public static boolean mayActOn(ServerPlayer self, UUID targetId) {
        return PhoneItem.isCarriedBy(self)
                && FriendData.get(self.server).areFriends(self.getUUID(), targetId);
    }

    /**
     * 只查手机 —— 给"对方还不是好友"的那几个操作用（发申请、答复申请、
     * 解除好友）。
     *
     * 这几个如果也要求是好友，功能本身就没了。
     */
    public static boolean carriesPhone(ServerPlayer self) {
        return PhoneItem.isCarriedBy(self);
    }
}
