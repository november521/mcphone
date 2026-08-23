package com.november.mcphone.feature.chat;

import net.minecraft.network.chat.Component;

/**
 * 好友操作的结果 —— 让"没成功"能说出是为什么。
 *
 * ============================================================
 * 为什么不能继续用 boolean
 * ============================================================
 *
 * 原先 sendFriendRequest 与 respondFriendRequest 都返回 boolean，而两个
 * 网络包处理函数把它整个丢掉，只回发一次最新状态。于是"加不上"这件事在
 * 玩家那边长这样：点了「+ 添加」，按钮闪了一下，还是「+ 添加」。
 *
 * 而它可能是四种完全不同的情况：我的好友满了、对方的好友满了、对方的
 * 申请列表满了、这个人服务端根本没见过。玩家分不出来，只会反复点，
 * 然后来报"加好友是坏的"。
 *
 * 一个 boolean 装不下这些区别，所以换成枚举，由网络层挑一句话告诉他。
 *
 * ============================================================
 * 为什么放在业务包而不是 net 包
 * ============================================================
 *
 * 它是 ChatService 的返回类型，属于业务结论，不是传输格式——现在没有
 * 任何一个包体带着它过网。放进 net 包会让人以为它是协议的一部分，
 * 日后加字段时被迫考虑兼容性，而它根本不需要。
 */
public enum FriendOutcome implements ChatOutcome {

    /** 成了：申请已发出，或已成为好友，或已拒绝 */
    OK,

    /**
     * 什么都没发生，也不必告诉玩家。
     *
     * 涵盖两类：正常客户端走不到的路径（身上没手机、申请不存在、
     * 加自己），以及"本来就是这个状态"（已经是好友了还要加）。
     * 前者只有伪造客户端能触发，多说无益；后者界面上本来就看得见。
     */
    NOTHING,

    /** 我的好友到顶了 */
    SELF_FULL,

    /** 对方的好友到顶了 */
    PEER_FULL,

    /** 对方待处理的申请到顶了，这条挤不进去 */
    PEER_INBOX_FULL,

    /** 服务端没见过这个人：没在线过，名字缓存与资料缓存里也都没有 */
    UNKNOWN_PLAYER;

    /**
     * 这一种要跟玩家说什么。
     *
     * 原先这段 switch 在 ChatNetworking 里。搬过来是因为"哪种结果配哪句话"
     * 是这个枚举自己的事，网络层只该管怎么送达——那边现在只剩一个 tell，
     * 加新功能不必再碰它。
     *
     * OK 与 NOTHING 都不说话：前者界面上自己看得见变化；后者要么是正常
     * 客户端走不到的路径（身上没手机、申请不存在），要么本来就是那个状态。
     */
    @Override
    public Component message() {
        String key = switch (this) {
            case SELF_FULL       -> "mcphone.chat.friend_self_full";
            case PEER_FULL       -> "mcphone.chat.friend_peer_full";
            case PEER_INBOX_FULL -> "mcphone.chat.friend_peer_inbox_full";
            case UNKNOWN_PLAYER  -> "mcphone.chat.friend_unknown_player";
            case OK, NOTHING     -> null;
        };
        // 上限那个参数只有 SELF_FULL 用得上，多传的参数会被翻译器忽略，
        // 为一句话单开一个分支不值得
        return key == null ? null : Component.translatable(key, FriendData.MAX_FRIENDS);
    }
}
