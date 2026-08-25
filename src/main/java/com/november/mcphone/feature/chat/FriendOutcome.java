package com.november.mcphone.feature.chat;

import net.minecraft.network.chat.Component;

/** 好友操作的结果，网络层据此挑一句话告诉玩家。属于业务结论，不过网，所以不在 net 包。 */
public enum FriendOutcome implements ChatOutcome {

    /** 申请已发出 / 已成为好友 / 已拒绝 */
    OK,

    /** 不提示：正常客户端走不到（没手机、申请不存在、加自己），或本来就是这个状态 */
    NOTHING,

    SELF_FULL,

    PEER_FULL,

    /** 对方待处理的申请到顶了 */
    PEER_INBOX_FULL,

    /** 没在线过，名字缓存与资料缓存里也都没有 */
    UNKNOWN_PLAYER;

    @Override
    public Component message() {
        String key = switch (this) {
            case SELF_FULL       -> "mcphone.chat.friend_self_full";
            case PEER_FULL       -> "mcphone.chat.friend_peer_full";
            case PEER_INBOX_FULL -> "mcphone.chat.friend_peer_inbox_full";
            case UNKNOWN_PLAYER  -> "mcphone.chat.friend_unknown_player";
            case OK, NOTHING     -> null;
        };
        // 上限参数只有 SELF_FULL 用得上，多传的参数翻译器会忽略
        return key == null ? null : Component.translatable(key, FriendData.MAX_FRIENDS);
    }
}
