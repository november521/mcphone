package com.november.mcphone.gui.app;

import com.november.mcphone.compat.WaystonesCompat;
import com.november.mcphone.network.OpenWaystoneSelectionPacket;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 传送石 App —— 在手机里打开传送石碑（Waystones）的选点界面。
 *
 * 界面是对方的原件：列表、排序、搜索、分组、传送特效一概不是我们画的。
 * 玩家看到的就是他手持传送石右键时看到的那一个。
 *
 * 点击只发包、不自己开界面：与末影箱同理，容器菜单必须由服务端建立才有
 * 权威性；何况目标列表、经验扣除、冷却、维度校验也只有服务端说了算。
 *
 * 代价按【传送石】计，不按背包按钮：服主怎么配传送石，这个 App 就怎么走。
 * 唯一的出入是不扣耐久——手机里本来就没有那块石头。我们没有发明任何平衡
 * 参数，要调就去改 Waystones 的 warpRequirements。细节见 {@link WaystonesCompat}。
 *
 * 贴图: assets/mcphone/textures/gui/app_icon_waystone.png (20×20)
 */
public final class WaystoneApp extends PhoneApp {

    public WaystoneApp() {
        super("waystone");
    }

    /**
     * 没装 Waystones 就当这个 App 不存在——主屏与应用商店里都不出现。
     *
     * 判断委托给兼容层而不是在这里直接查 ModList：modid 字符串只该有一处
     * 权威来源，两处各写一份，改动时漏一处就会出现"商店里有、点了没反应"
     * 这种最难查的状态。
     */
    @Override
    public boolean isAvailable() {
        return WaystonesCompat.isLoaded();
    }

    /**
     * 不预装 —— 要从应用商店买，价格是一块传送石（见 BuiltinAppPrices）。
     *
     * 理由与末影箱 App 一样：预装的话它本来就在主屏上，永远进不了商店，
     * 价格也就永远不会被触发。老存档不受影响，已经装着的不会被收走。
     */
    @Override
    public boolean isPreinstalled() {
        return false;
    }

    @Override
    public void onPress() {
        PacketDistributor.sendToServer(new OpenWaystoneSelectionPacket());
    }
}
