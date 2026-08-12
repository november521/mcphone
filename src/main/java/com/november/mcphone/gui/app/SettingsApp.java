package com.november.mcphone.gui.app;

import net.minecraft.client.Minecraft;
import com.november.mcphone.gui.PhoneScreen;

/**
 * 设置 App —— 打开手机设置列表。
 *
 * 贴图: assets/mcphone/textures/gui/app_icon_settings.png (20×20)
 */
public final class SettingsApp extends PhoneApp {

    public SettingsApp() {
        super("settings");
    }

    /**
     * 设置是唯一的系统 App，不可卸载。
     * 它是进入 App 管理器的唯一入口，一旦卸载玩家将无法再装回任何 App。
     */
    @Override
    public boolean isSystemApp() { return true; }

    @Override
    public void onPress() {
        if (Minecraft.getInstance().screen instanceof PhoneScreen ps) {
            ps.navigateTo(PhoneScreen.Mode.SETTINGS);
        }
    }
}
