package com.november.mcphone.feature.camera.client;

import com.november.mcphone.core.client.PhoneKeys;
import com.november.mcphone.platform.client.port.PhoneGraphics;
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
            // 文件名里带上坐标，相册看大图时用界面文字显示它——烧在照片上那一行在
            // 一百来像素宽的手机屏幕上只剩一两个像素高，读不出来。见 CameraStamp。
            // 水印关着时给 null，那就是原版自己的起名规矩
            Screenshot.grab(mc.gameDirectory,
                    CameraStamp.fileName(mc.gameDirectory, mc.player),
                    mc.getMainRenderTarget(), 1,
                    msg -> {
                        if (mc.player != null) com.november.mcphone.platform.PlayerAccess.message(mc.player, msg, true);
                    });
        }

        // 退出优先于拍照：同一 tick 内两键同时按下时以退出为准
        boolean exitPressed = false;
        while (PhoneKeys.CAMERA_EXIT.consumeClick()) exitPressed = true;
        if (exitPressed) {
            CameraMode.exit();
            return;
        }

        while (PhoneKeys.CAMERA_SHUTTER.consumeClick()) {
            CameraMode.requestCapture();
        }
    }

    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!CameraMode.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        PhoneGraphics.withIsolatedPose(event.getGuiGraphics(), graphics -> {
            // 戳和取景框必须共用上下文：戳的右下角平移不能成为下一层的初始矩阵。
            // 干净帧仍保留坐标戳；提前返回或绘制中断都由作用域恢复原版矩阵。
            CameraStamp.render(graphics, mc.font, w, h);

            if (CameraMode.suppressOverlay()) {
                CameraMode.markCleanFrame();
                return;
            }

            CameraOverlay.render(graphics, mc.font, w, h, System.currentTimeMillis(),
                    event.getPartialTick().getGameTimeDeltaPartialTick(false));
        });
    }

    /** 安全网：打开任意界面就退出相机模式，否则玩家会卡在没有 HUD 的状态里 */
    public static void onScreenOpening(ScreenEvent.Opening event) {
        CameraMode.exit();
    }
}
