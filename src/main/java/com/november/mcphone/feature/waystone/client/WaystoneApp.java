package com.november.mcphone.feature.waystone.client;

import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.compat.WaystonesCompat;
import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.feature.waystone.net.OpenWaystoneSelectionPacket;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 传送石 App：在手机里打开 Waystones 原版的选点界面。代价按传送石计、只是不扣耐久，细节见 {@link WaystonesCompat}。
 * 贴图: assets/mcphone/textures/app/waystone.png (20×20)
 */
public final class WaystoneApp extends PhoneApp {

    public WaystoneApp() {
        super("waystone");
    }

    // 声明前置而不是覆盖 isAvailable()："可用性"与"缺什么"必须是同一个来源；modid 取兼容层的常量
    @Override
    public List<RequiredMod> requiredMods() {
        return List.of(new RequiredMod(
                WaystonesCompat.WAYSTONES_MODID,
                Component.translatable("mcphone.compat.waystones").getString()));
    }

    /** 不预装：要从应用商店买，价格见 BuiltinAppPrices。老存档已装着的不会被收走 */
    @Override
    public boolean isPreinstalled() {
        return false;
    }

    @Override
    public void onPress() {
        // 只发包不自己开界面：菜单与传送的校验都只有服务端说了算
        PacketDistributor.sendToServer(new OpenWaystoneSelectionPacket());
    }
}
