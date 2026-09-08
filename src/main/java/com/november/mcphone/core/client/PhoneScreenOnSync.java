package com.november.mcphone.core.client;

import com.november.mcphone.core.PhoneLocation;
import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.core.net.PhoneScreenOnPacket;
import net.minecraft.client.Minecraft;

import java.util.Optional;

/**
 * 把"我手上那部手机亮着"报给服务端 —— 别人看到的模型才会跟着切成白屏。
 *
 * 为什么要经过服务端
 *
 * 手机界面是纯客户端的，只有本机知道自己开着它。物品模型要在<b>别人</b>屏幕上也是亮的，
 * 判断依据就得放在同步得出去的地方——物品堆上的组件，而组件只有服务端写得了。这个类是那条
 * 链的第一环：开机/关机时报一次；服务端写组件；组件随手持物品同步给所有看得见这只手的人；
 * {@link PhoneItemProperties} 在渲染时读它。
 *
 * 只在开关机那一下发，不轮询
 *
 * 手机的"生"与"死"各只有一个收口，所以不必每 tick 去算：
 *
 *   开机  {@code new PhoneScreen(...)} —— 全屏开的、HUD 挂上的，都从这儿来
 *   关机  {@code PhoneScreen.shutdown()} —— 它自己的注释写着"只该有两个调用方"，
 *         被别的界面顶掉、ESC、点机身外、手机离开副手，最后都汇到这一句
 *   挪位  {@code PhoneScreen.relocate(...)} —— 手机在身上换了个地方，人还开着它
 *
 * 关机那一下是<b>重新算一次</b>而不是直接报灭：全屏开着的那部关掉之后，副手 HUD 上可能还
 * 挂着另一部亮着的（玩家身上带两部时会这样），那时候该把亮的换成它，而不是全灭。
 *
 * 只管拿在手上的那部
 *
 * 手机在背包里、饰品栏里的时候，那件物品在别人眼里根本不渲染，点亮它没有任何人看得见，
 * 只是白发一个包、白占一个组件。所以位置不是"在手上"就当没亮——包也不发。
 */
public final class PhoneScreenOnSync {

    private PhoneScreenOnSync() {}

    /** 上一次报给服务端的状态，用来去重：不变就不发 */
    private static Optional<PhoneLocation> lastSent = Optional.empty();

    /** 有手机开机了，或者开着的那部换了地方 */
    static void turnedOn(PhoneLocation location) {
        send(litNow(location, null));
    }

    /**
     * 有手机关机了 —— 重新算一次谁还亮着。
     *
     * @param closing 正在关的那部，算的时候要把它排除掉：这一句是在它真正拆完之前调的，
     *                那时它可能还挂在 {@link PhoneHud} 的字段上
     */
    static void turnedOff(PhoneScreen closing) {
        send(litNow(null, closing));
    }

    /**
     * 这会儿该亮的是哪一件：先看给的那个位置，再看副手 HUD 上那部。
     *
     * 为什么关机、开机都要回头看 HUD 一眼：玩家身上可以有两部——副手挂着一部（HUD 上一直
     * 亮着），手里/背包里另开一部。关掉后开的那部时，亮的该换回 HUD 那部而不是全灭；反过来
     * 开了一部在背包里的（点亮不了，别人看不见背包），也不该把 HUD 那部的光顺手灭掉。
     *
     * 两个位置都不在手上就是"没亮"：那件物品在别人眼里根本不渲染，点亮它谁也看不见。
     */
    private static Optional<PhoneLocation> litNow(PhoneLocation preferred, PhoneScreen exclude) {
        if (preferred instanceof PhoneLocation.InHand) return Optional.of(preferred);

        PhoneScreen hud = PhoneHud.hudPhone();
        if (hud == null || hud == exclude) return Optional.empty();

        PhoneLocation onHud = hud.location();
        return onHud instanceof PhoneLocation.InHand ? Optional.of(onHud) : Optional.empty();
    }

    /**
     * 离开世界：把去重用的记忆清空。
     *
     * 不发包——连接正在拆，而且服务端那个组件本来就不落盘，玩家的手机下次进来自然是灭的。
     * 清掉是为了下一局能重发：不清的话，进新世界开手机时会因为"和上次一样"被判成没变。
     */
    public static void forget() {
        lastSent = Optional.empty();
    }

    private static void send(Optional<PhoneLocation> lit) {
        if (lit.equals(lastSent)) return;

        // 断线过程中会走到这儿（拆世界时手机跟着关机），那时候发包会炸
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            lastSent = Optional.empty();
            return;
        }

        lastSent = lit;
        MCphoneNetwork.sendToServer(new PhoneScreenOnPacket(lit));
    }
}
