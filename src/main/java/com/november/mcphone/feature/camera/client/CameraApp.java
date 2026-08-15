package com.november.mcphone.feature.camera.client;

import com.november.mcphone.feature.camera.client.CameraMode;
import net.minecraft.client.Minecraft;
import com.november.mcphone.core.client.PhoneApp;

/**
 * 相机 App —— 进入相机模式拍照。
 *
 * 点击后手机界面关闭，进入覆盖在游戏画面上的相机模式：
 * 藏起原版 HUD（等效 F1）、叠加取景框，按拍照键走原版截图流程
 * 存入 <游戏目录>/screenshots/，与 F2 完全一致。
 *
 * 按键可在原版「选项 → 按键设置 → MCphone」中修改，
 * 见 {@link com.november.mcphone.core.client.MCphoneKeyBindings}。
 *
 * 贴图: assets/mcphone/textures/app/camera.png (20×20)
 */
public final class CameraApp extends PhoneApp {

    public CameraApp() {
        super("camera");
    }

    /** 非预装：默认不在主屏，玩家可从应用商店下载 */
    @Override
    public boolean isPreinstalled() { return false; }

    @Override
    public void onPress() {
        Minecraft mc = Minecraft.getInstance();

        // 顺序不能反：必须先关掉手机界面再进入相机模式。
        // CameraHandler 监听了 ScreenEvent.Opening 作为安全网，
        // 若先进相机再动界面，可能被那道安全网立刻踢出相机模式。
        mc.setScreen(null);
        CameraMode.enter();
    }
}
