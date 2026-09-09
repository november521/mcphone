package com.november.mcphone.core.client;

import com.november.mcphone.core.PhoneLocation;
import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.core.net.PhoneScreenOnPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Optional;

/**
 * 把"我这会儿开着的是哪一台设备"报给服务端 —— 别人看到的模型才会跟着切成白屏。
 *
 * <h2>为什么要经过服务端</h2>
 *
 * 手机界面是纯客户端的，只有本机知道自己开着它。物品模型要在<b>别人</b>屏幕上也是亮的，
 * 判断依据就得放在同步得出去的地方——物品堆上的组件，而组件只有服务端写得了。这个类是那条
 * 链的第一环：本机每 tick 报一次（只在变了的时候）；服务端写组件；组件随手持物品同步给所有
 * 看得见这只手的人；{@link PhoneItemProperties} 在渲染时读它。
 *
 * <h2>每 tick 算一次，而不是开关机时各报一次</h2>
 *
 * 从前是边沿触发的：开机报一次、关机报一次、挪位再报一次。那要求<b>每一处</b>让设备生或死的
 * 地方都记得报一句——全屏开、HUD 挂上、ESC、点机身外、被别的界面顶掉、手机离开副手、按 G
 * 收起 HUD……漏掉任何一处，玩家身上就会出现一台"界面开着、屏幕却黑着"的机器，而且不报错。
 * 收起 HUD 那一处就漏过：手里全屏开着平板时把副手的手机收起来，平板的屏幕跟着一起灭了。
 *
 * 而"这会儿亮的是哪一台"本来就是个<b>纯查询</b>：全屏那台、HUD 那台，谁拿在手上谁亮
 * （见 {@link #litNow()}）。每 tick 问一遍，谁都不必记得报告，也就没有"漏报"这回事。
 *
 * 代价只是最多晚一 tick（50 毫秒）—— 而收益是这一类 bug 不会再出现。发包仍然只在<b>答案变了</b>
 * 的时候（{@link #send}），开关手机是很低频的事，这条线上一分钟也走不了几个包。
 *
 * <h2>只管拿在手上的那台</h2>
 *
 * 设备在背包里、饰品栏里的时候，那件物品在别人眼里根本不渲染，点亮它没有任何人看得见，
 * 只是白发一个包、白占一个组件。所以位置不是"在手上"就当没亮——包也不发。
 */
public final class PhoneScreenOnSync {

    private PhoneScreenOnSync() {}

    /** 上一次报给服务端的状态，用来去重：不变就不发 */
    private static Optional<PhoneLocation> lastSent = Optional.empty();

    /**
     * 由 MCphoneClient 构造函数挂到游戏总线。
     *
     * 排在 {@link PhoneHud#onClientTick} 之后只是为了同一 tick 内就能报上，早一 tick 晚一 tick
     * 都不影响正确性——下一 tick 照样会算出同一个答案。
     */
    public static void onClientTick(ClientTickEvent.Post event) {
        send(litNow());
    }

    /**
     * 这会儿该亮的是哪一件 —— 两处开着的设备，谁拿在手上算谁。
     *
     * 一台设备的屏幕能在两个地方亮着，而且可以<b>同时</b>亮：
     *
     *   全屏那一台  {@code mc.screen} 就是它（右键设备、快捷键、Alt 唤出鼠标）
     *   HUD 那一台  {@link PhoneHud#hudPhone()}（副手挂着的，或者按 G 叫出来的）
     *
     * 玩家身上带两台时（副手挂手机、手里拿平板）这是两台不同的机器，两台的界面可以同时开着。
     * 而服务端那个组件一次只记一台，所以这里要挑一台：全屏那台优先——玩家正盯着的是它。
     *
     * 两处都不在手上就是"没亮"：那件物品在别人眼里根本不渲染，点亮它谁也看不见。
     */
    private static Optional<PhoneLocation> litNow() {
        Optional<PhoneLocation> fullscreen = inHandOf(fullscreenPhone());
        return fullscreen.isPresent() ? fullscreen : inHandOf(PhoneHud.hudPhone());
    }

    /** 全屏开着的那一台，没有就 null。容器界面（末影箱、唱片仓）不算——那不是一台设备的屏幕 */
    private static PhoneScreen fullscreenPhone() {
        return Minecraft.getInstance().screen instanceof PhoneScreen screen ? screen : null;
    }

    /** 这台开着的设备拿在手上吗；是就把位置给出来。没有这台、或者它不在手上，都算没亮 */
    private static Optional<PhoneLocation> inHandOf(PhoneScreen screen) {
        if (screen == null) return Optional.empty();

        PhoneLocation location = screen.location();
        return location instanceof PhoneLocation.InHand ? Optional.of(location) : Optional.empty();
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
