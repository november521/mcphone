package com.november.mcphone.feature.store.client;

import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.core.client.PhoneScreen;
import net.minecraft.client.Minecraft;

/**
 * 应用商店 App，下载安装其他 App；来源可拓展，见 api.client.store.IAppSource。
 * 贴图: assets/mcphone/textures/app/app_store.png (20×20)
 */
public final class AppStoreApp extends PhoneApp {

    public AppStoreApp() {
        super("app_store");
    }

    /** 不可卸载：它是装回其他 App 的唯一入口 */
    @Override
    public boolean isSystemApp() { return true; }

    @Override
    public void onPress() {
        if (Minecraft.getInstance().screen instanceof PhoneScreen ps) {
            ps.navigateTo(PhoneScreen.Mode.APP_STORE);
        }
    }
}
