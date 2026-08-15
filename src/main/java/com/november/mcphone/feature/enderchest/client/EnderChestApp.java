package com.november.mcphone.feature.enderchest.client;

import com.november.mcphone.feature.enderchest.net.OpenEnderChestPacket;
import net.neoforged.neoforge.network.PacketDistributor;
import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.feature.store.BuiltinAppPrices;

/**
 * 便携末影箱 App —— 在手机里打开自己的末影箱。
 *
 * 内容就是原版末影箱，与方块末影箱、跨维度完全互通。
 *
 * 点击只发包、不自己开界面：容器菜单必须由服务端建立才有权威性，
 * 界面会在服务端 openMenu 后由原版流程自动弹出。
 *
 * 贴图: assets/mcphone/textures/app/ender_chest.png (20×20)
 */
public final class EnderChestApp extends PhoneApp {

    public EnderChestApp() {
        super("ender_chest");
    }

    /**
     * 不预装 —— 要从应用商店买，价格是一个末影箱（见 BuiltinAppPrices）。
     *
     * 预装的话它本来就在主屏上，永远不会出现在商店里，那条价格也就永远
     * 不会被触发，等于一套看不见的机制。
     *
     * 老存档不受影响：PhoneScreenRegistry.loadState 靠 known 记住每个玩家
     * 的历史选择，已经装着的不会被收走。这个改动只对新玩家生效。
     */
    @Override
    public boolean isPreinstalled() {
        return false;
    }

    @Override
    public void onPress() {
        PacketDistributor.sendToServer(new OpenEnderChestPacket());
    }
}
