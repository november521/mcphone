package com.november.mcphone.feature.enderchest.client;

import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.feature.enderchest.net.OpenEnderChestPacket;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 便携末影箱 App：打开自己的末影箱，与方块末影箱、跨维度完全互通。
 * 贴图: assets/mcphone/textures/app/ender_chest.png (20×20)
 */
public final class EnderChestApp extends PhoneApp {

    public EnderChestApp() {
        super("ender_chest");
    }

    /** 不预装：要从应用商店买，价格见 BuiltinAppPrices。老存档已装着的不会被收走 */
    @Override
    public boolean isPreinstalled() {
        return false;
    }

    @Override
    public void onPress() {
        // 只发包不自己开界面：容器菜单必须由服务端 openMenu 建立，界面由原版流程自动弹出
        PacketDistributor.sendToServer(new OpenEnderChestPacket());
    }
}
