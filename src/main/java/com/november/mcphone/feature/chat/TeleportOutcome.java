package com.november.mcphone.feature.chat;

import net.minecraft.network.chat.Component;

/** 传送操作的结果，网络层据此挑一句话告诉玩家。 */
public enum TeleportOutcome implements ChatOutcome {

    OK,

    /** 没手机 / 不是好友 / 传给自己：正常客户端走不到，不提示，也不给伪造客户端泄露是哪条规则拦的 */
    NOTHING,

    /** 在线状态 3 秒才刷一次，正常客户端也撞得上，必须提示 */
    PEER_OFFLINE,

    /** 服主关了功能，必须提示，不能并进 NOTHING */
    DISABLED;

    @Override
    public Component message() {
        return switch (this) {
            case PEER_OFFLINE -> Component.translatable("mcphone.chat.teleport_offline");
            case DISABLED -> Component.translatable("mcphone.chat.teleport_disabled");
            case OK, NOTHING -> null;
        };
    }
}
