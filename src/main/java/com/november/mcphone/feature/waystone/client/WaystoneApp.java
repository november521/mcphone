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

    /**
     * Waystones 是【联动】，不是前置 —— MCphone 自己一个前置都没有，缺了它少的是
     * 这一个 App，不是手机开不了机。声明成联动之后玩家照样找得到它：商店的
     * 「联动App」页对当前不可用的 App 会回退来读这里，写明缺的是哪个模组。
     *
     * modid 取兼容层的常量："可用性"与"缺什么"必须是同一个来源。
     */
    @Override
    public List<RequiredMod> companionMods() {
        return List.of(new RequiredMod(
                WaystonesCompat.WAYSTONES_MODID,
                Component.translatable("mcphone.compat.waystones").getString()));
    }

    /**
     * 没有 Waystones 就没有传送点可选，这一格不该出现。
     *
     * 必须自己判：联动声明不参与默认的可用性判断（默认实现只看 requiredMods），
     * 照默认走就是"永远可用"，没装 Waystones 时主屏上会多一个点了没反应的图标。
     * 走兼容层的那个方法，与上面的 modid 是同一个来源。
     */
    @Override
    public boolean isAvailable() {
        return WaystonesCompat.isLoaded();
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
