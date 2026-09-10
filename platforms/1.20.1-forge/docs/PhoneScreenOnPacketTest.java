package com.november.mcphone.core.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;

/** 双手亮屏位的线格式与越界位清理断言。 */
public class PhoneScreenOnPacketTest {

    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static PhoneScreenOnPacket roundTrip(int mask) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        PhoneScreenOnPacket.encode(new PhoneScreenOnPacket(mask), buffer);
        check(buffer.readableBytes() == 1, "亮屏掩码必须只占一个字节");
        return PhoneScreenOnPacket.decode(buffer);
    }

    public static void main(String[] args) {
        for (int mask = 0; mask <= PhoneScreenOnPacket.ALL_HANDS; mask++) {
            PhoneScreenOnPacket packet = roundTrip(mask);
            check(packet.litHands() == mask, "掩码往返失败: " + mask);
            check(packet.isLit(InteractionHand.MAIN_HAND)
                            == ((mask & PhoneScreenOnPacket.MAIN_HAND) != 0),
                    "主手判定失败: " + mask);
            check(packet.isLit(InteractionHand.OFF_HAND)
                            == ((mask & PhoneScreenOnPacket.OFF_HAND) != 0),
                    "副手判定失败: " + mask);
        }

        check(new PhoneScreenOnPacket(0xff).litHands() == PhoneScreenOnPacket.ALL_HANDS,
                "越界位必须清掉");
        System.out.println("全部通过（" + checks + " 项）");
    }
}
