package com.november.mcphone.gui.app;

import com.november.mcphone.gui.PhoneScreen;
import net.minecraft.client.Minecraft;

/**
 * 应用商店 App —— 下载安装其他 App。
 *
 * 目前只列出本机已发现但未安装的 App（未预装的、或被玩家卸载过的）。
 * 来源可拓展，见 com.november.mcphone.api.client.store.IAppSource。
 *
 * 贴图: assets/mcphone/textures/gui/app_icon_app_store.png (20×20)
 */
public final class AppStoreApp extends PhoneApp {

    public AppStoreApp() {
        super("app_store");
    }

    /**
     * 应用商店是系统 App，不可卸载。
     * 与设置同理：它是安装 App 的唯一入口，卸载后玩家将无法再装回任何 App。
     */
    @Override
    public boolean isSystemApp() { return true; }

    @Override
    public void onPress() {
        if (Minecraft.getInstance().screen instanceof PhoneScreen ps) {
            ps.navigateTo(PhoneScreen.Mode.APP_STORE);
        }
    }
}
