package com.november.mcphone.feature.chat.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** 本人与另一个玩家的关系，决定"加联系人"界面显示什么按钮。由服务端算好带下来。 */
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

    /** 按序号传输；越界退回 NONE，宁可显示成陌生人也不抛异常断线 */
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
