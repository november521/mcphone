package com.november.mcphone.network.chat;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 本人与另一个玩家的关系 —— 决定"加联系人"界面上那一行显示什么按钮。
 *
 * 原先只有一个 isContact 布尔量，双向好友做不到：既要区分"我发了申请
 * 在等对方"和"对方发了申请等我处理"，一个布尔量表达不了四种状态。
 *
 * 由服务端算好带下来。客户端自己比对也能算，但那要求它手里同时有好友表、
 * 发出的申请、收到的申请三份数据，白白多同步一堆东西。
 */
public enum Relation {
    /** 陌生人，可以发好友申请 */
    NONE,
    /** 已是好友 */
    FRIEND,
    /** 我发过申请，等对方处理 */
    REQUEST_SENT,
    /** 对方发来申请，等我处理 */
    REQUEST_RECEIVED;

    private static final Relation[] VALUES = values();

    /**
     * 按序号传输。
     *
     * 解码时序号越界一律退回 NONE：伪造客户端或版本不一致时收到未知值，
     * 宁可显示成陌生人，也不要抛异常把整条连接打断。
     */
    public static final StreamCodec<ByteBuf, Relation> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public Relation decode(ByteBuf buf) {
                    int ordinal = ByteBufCodecs.VAR_INT.decode(buf);
                    return (ordinal >= 0 && ordinal < VALUES.length) ? VALUES[ordinal] : NONE;
                }

                @Override
                public void encode(ByteBuf buf, Relation value) {
                    ByteBufCodecs.VAR_INT.encode(buf, value.ordinal());
                }
            };
}
