package com.november.mcphone.feature.chat.net;

import net.minecraft.network.FriendlyByteBuf;

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
    public static void encode(Relation value, FriendlyByteBuf buf) {
        buf.writeVarInt(value.ordinal());
    }

    public static Relation decode(FriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        return (ordinal >= 0 && ordinal < VALUES.length) ? VALUES[ordinal] : NONE;
    }
}
