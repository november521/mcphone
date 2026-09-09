package com.november.mcphone.feature.chat;

import com.november.mcphone.core.PhoneItem;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 对好友做写操作前的安全边界：手机得在身上、对方得是好友。
 * 「对方在线」故意不并进来，调用方要区分"不是好友"（静默）和"刚下线"（提示）。只读请求（拉列表、拉历史）不校验手机。
 */
public final class FriendGuard {

    private FriendGuard() {}

    /** 手机在身上（主手/副手/背包/饰品槽）且对方是好友；好友这条同时堵住对随便编造的 UUID 操作 */
    public static boolean mayActOn(ServerPlayer self, UUID targetId) {
        return PhoneItem.isCarriedBy(self)
                && FriendData.get(self.server).areFriends(self.getUUID(), targetId);
    }

    /** 只查手机，给"对方还不是好友"的操作用（发申请、答复申请、解除好友） */
    public static boolean carriesPhone(ServerPlayer self) {
        return PhoneItem.isCarriedBy(self);
    }
}
