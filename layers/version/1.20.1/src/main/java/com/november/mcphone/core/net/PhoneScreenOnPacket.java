package com.november.mcphone.core.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;

/** 客户端用两位掩码同步主手和副手的设备亮屏状态。 */
public record PhoneScreenOnPacket(int litHands) {

    public static final int MAIN_HAND = 1;
    public static final int OFF_HAND = 2;
    public static final int ALL_HANDS = MAIN_HAND | OFF_HAND;

    public PhoneScreenOnPacket {
        litHands &= ALL_HANDS;
    }

    public boolean isLit(InteractionHand hand) {
        int bit = hand == InteractionHand.MAIN_HAND ? MAIN_HAND : OFF_HAND;
        return (litHands & bit) != 0;
    }

    public static void encode(PhoneScreenOnPacket msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.litHands());
    }

    public static PhoneScreenOnPacket decode(FriendlyByteBuf buf) {
        return new PhoneScreenOnPacket(buf.readUnsignedByte());
    }
}
