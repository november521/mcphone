package com.november.mcphone.core.client;

import com.november.mcphone.core.PhoneLocation;
import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.core.net.PhoneScreenOnPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** 每 tick 同步两只手的设备亮屏状态，只在状态或手持物变化时发包。 */
public final class PhoneScreenOnSync {

    private PhoneScreenOnSync() {}

    /** 状态相同但手持物换了也要重发，否则新设备会沿用旧设备的黑白屏。 */
    private static int lastSent = -1;
    private static ItemStack lastMainHand = ItemStack.EMPTY;
    private static ItemStack lastOffHand = ItemStack.EMPTY;

    public static void tick() {
        send(litNow());
    }

    /** 全屏和平板 HUD 可以各占一只手，亮屏位必须取并集。 */
    private static int litNow() {
        return handBit(fullscreenPhone()) | handBit(PhoneHud.hudPhone());
    }

    private static PhoneScreen fullscreenPhone() {
        return Minecraft.getInstance().screen instanceof PhoneScreen screen ? screen : null;
    }

    private static int handBit(PhoneScreen screen) {
        if (screen == null) return 0;
        if (!(screen.location() instanceof PhoneLocation.InHand inHand)) return 0;
        return inHand.hand() == InteractionHand.MAIN_HAND
                ? PhoneScreenOnPacket.MAIN_HAND : PhoneScreenOnPacket.OFF_HAND;
    }

    public static void forget() {
        lastSent = -1;
        lastMainHand = ItemStack.EMPTY;
        lastOffHand = ItemStack.EMPTY;
    }

    private static void send(int lit) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            forget();
            return;
        }

        ItemStack main = mc.player.getMainHandItem();
        ItemStack off = mc.player.getOffhandItem();
        if (lit == lastSent
                && ItemStack.matches(main, lastMainHand)
                && ItemStack.matches(off, lastOffHand)) return;

        lastSent = lit;
        lastMainHand = main.copy();
        lastOffHand = off.copy();
        MCphoneNetwork.sendToServer(new PhoneScreenOnPacket(lit));
    }
}
