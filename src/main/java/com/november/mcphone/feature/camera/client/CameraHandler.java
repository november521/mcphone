package com.november.mcphone.feature.camera.client;

import com.november.mcphone.core.client.MCphoneKeyBindings;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 相机模式的事件监听。Fabric 侧由 MCphoneClient 挂到各个客户端事件上：
 * tick（{@code ClientTickEvents}）、HUD 渲染（{@code HudRenderCallback}）、
 * 屏幕打开（{@code ScreenEvents.BEFORE_INIT}）。
 */
public final class CameraHandler {

    private CameraHandler() {}

    public static void onClientTick() {
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

    public static void onRenderGui(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (!CameraMode.isActive()) return;

        // 拍照期间必须跳过取景框，否则会被拍进照片
        if (CameraMode.suppressOverlay()) {
            CameraMode.markCleanFrame();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        CameraOverlay.render(
                guiGraphics,
                mc.font,
                mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getGuiScaledHeight(),
                System.currentTimeMillis(),
                // 模糊后处理要它来插值。false ＝ 不算暂停时的那一份，相机模式下
                // 游戏本来就没暂停，两者一样，取跟着游戏时间的那个更合语义
                deltaTracker.getGameTimeDeltaPartialTick(false));
    }

    /** 安全网：打开任意界面就退出相机模式，否则玩家会卡在没有 HUD 的状态里 */
    public static void onScreenOpening() {
        CameraMode.exit();
    }
}
