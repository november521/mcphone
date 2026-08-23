package com.november.mcphone.feature.chat;

import net.minecraft.network.chat.Component;

/**
 * 传送操作的结果 —— 与 {@link FriendOutcome} 同一个用意：让"没成功"能说出
 * 是为什么，由网络层挑一句话告诉玩家。
 *
 * 只有三个值，因为传送只有三种下场：走了、什么都没发生、对方不在了。
 * 不必像好友那边分出四种失败——那边的每一种都是玩家自己能补救的
 * （解除一个再加、等对方处理），这边不是。
 */
public enum TeleportOutcome implements ChatOutcome {

    /** 传过去了 */
    OK,

    /**
     * 什么都没发生，也不必告诉玩家。
     *
     * 涵盖三类正常客户端走不到的路径：身上没手机、对方不是好友、传给自己。
     * 后两者只有伪造客户端能触发——界面上的按钮只画在在线好友那一行，
     * 自己更不会出现在自己的好友列表里。多说无益，也不该告诉对方
     * 是哪条规则拦住了它。
     */
    NOTHING,

    /**
     * 对方已经下线了。
     *
     * 这一条【必须】告诉玩家，而且正常客户端撞得上：会话列表 3 秒才刷一次
     * 在线状态，那 3 秒里对方随时可能退出游戏。玩家看到的是一个绿点、点了
     * 却没反应，不解释的话他只会以为传送坏了。
     */
    PEER_OFFLINE,

    /**
     * 服主把这个功能关了。
     *
     * 必须告诉玩家，而且不能与 NOTHING 合并：那几种是"正常客户端走不到"，
     * 而这一种是玩家的手机上明明有过这个按钮（配置改之前）、或者他见别人
     * 用过。不解释的话他只会以为坏了，然后来问。
     */
    DISABLED;

    /**
     * 只有"对方下线了"要说。OK 时人已经到了，眼睛看得见；NOTHING 那几种
     * 正常客户端走不到，告诉它是哪条规则拦住的等于帮伪造客户端调试。
     */
    @Override
    public Component message() {
        return switch (this) {
            case PEER_OFFLINE -> Component.translatable("mcphone.chat.teleport_offline");
            case DISABLED -> Component.translatable("mcphone.chat.teleport_disabled");
            case OK, NOTHING -> null;
        };
    }
}
