package com.november.mcphone.feature.camera.client;

import com.november.mcphone.core.client.MCphoneKeyBindings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 相机模式的事件监听。三个事件都在游戏总线（NeoForge.EVENT_BUS），由 MCphoneClient 显式 addListener；
 * 按键的注册在模组总线，见 MCphoneKeyBindings。拍照的分帧时序见 {@link CameraMode} 的类注释。
 */
public final class CameraHandler {

    private CameraHandler() {}

    public static void onClientTick(ClientTickEvent.Post event) {
        if (!CameraMode.isActive()) return;

        Minecraft mc = Minecraft.getInstance();

        // 上一帧已是不含取景框的干净画面，可以抓取了
        if (CameraMode.shouldGrabNow()) {
            CameraMode.finishCapture();
            Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), msg -> {
                if (mc.player != null) mc.player.displayClientMessage(msg, true);
            });
        }

        // 退出优先于拍照：同一 tick 内两键同时按下时以退出为准
        boolean exitPressed = false;
        while (MCphoneKeyBindings.CAMERA_EXIT.consumeClick()) exitPressed = true;
        if (exitPressed) {
            CameraMode.exit();
            return;
        }

        while (MCphoneKeyBindings.CAMERA_SHUTTER.consumeClick()) {
            CameraMode.requestCapture();
        }
    }

    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!CameraMode.isActive()) return;

        // 拍照期间必须跳过取景框，否则会被拍进照片
        if (CameraMode.suppressOverlay()) {
            CameraMode.markCleanFrame();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        CameraOverlay.render(
                event.getGuiGraphics(),
                mc.font,
                mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getGuiScaledHeight(),
                System.currentTimeMillis());
    }

    /** 安全网：打开任意界面就退出相机模式，否则玩家会卡在没有 HUD 的状态里 */
    public static void onScreenOpening(ScreenEvent.Opening event) {
        CameraMode.exit();
    }
}
