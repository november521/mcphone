package com.november.mcphone.feature.camera.client;

import com.november.mcphone.core.client.AppOptions;
import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.feature.camera.client.CameraMode;
import net.minecraft.client.Minecraft;

/**
 * 相机 App：关掉手机界面，进入覆盖在游戏画面上的相机模式；拍照走原版截图流程，与 F2 完全一致。
 * 按键在原版「按键设置 → MCphone」里改，见 {@link com.november.mcphone.core.client.MCphoneKeyBindings}。
 * 贴图: assets/mcphone/textures/app/camera.png (20×20)
 */
public final class CameraApp extends PhoneApp {

    public CameraApp() {
        super("camera");

        // 「快门闪光：白闪 / 模糊」那一行。在这儿登记而不是在 MCphoneClient 里，
        // 是因为这里 getId() 现成——在别处登记就得把 "mcphone:camera" 再写一遍
        AppOptions.register(getId(), CameraFlash.appOption());
    }

    @Override
    public void onPress() {
        Minecraft mc = Minecraft.getInstance();

        // 顺序不能反：CameraHandler 监听 ScreenEvent.Opening 作安全网，
        // 先进相机再动界面会被它立刻踢出相机模式
        mc.setScreen(null);
        CameraMode.enter();
    }
}
