package com.november.mcphone.feature.chat;

import net.minecraft.network.chat.Component;

/** 发图片的结果，网络层据此挑一句话告诉玩家。 */
public enum ImageOutcome implements ChatOutcome {

    OK,

    /** 没手机 / 不是好友：正常客户端走不到，不提示，也不告诉伪造客户端是哪条规则拦的 */
    NOTHING,

    /** 服主关了发图片，必须提示——按钮虽然藏了，但玩家可能是刚换到这台服务器 */
    DISABLED,

    /** 上一张刚发出去没多久。丢掉一张图玩家看得见（气泡没出来），所以要说一句 */
    TOO_FAST,

    /** 收到的不是一张认得出的 PNG，或者根本没收全 */
    BROKEN,

    /** 服务端写不进硬盘。玩家什么都没做错，但必须知道这张图没发出去 */
    STORE_FAILED;

    @Override
    public Component message() {
        return switch (this) {
            case DISABLED     -> Component.translatable("mcphone.chat.image_disabled");
            case TOO_FAST     -> Component.translatable("mcphone.chat.image_too_fast");
            case BROKEN       -> Component.translatable("mcphone.chat.image_broken");
            case STORE_FAILED -> Component.translatable("mcphone.chat.image_store_failed");
            case OK, NOTHING  -> null;
        };
    }
}
