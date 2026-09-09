package com.november.mcphone.feature.camera.client;

import com.november.mcphone.core.client.PhoneKeys;
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
                    mc.getMainRenderTarget(),
                    msg -> {
                        if (mc.player != null) mc.player.displayClientMessage(msg, true);
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

        // 坐标戳画在下面那个 return 【之前】—— 那个 return 的意思正是"这一帧要被拍下来"，
        // 而它是相机里唯一要留在照片上的东西。位置换到 return 之后，照片上就没有坐标了，
        // 而取景时照样看得见：这个错法在开发环境里看不出来。见 CameraStamp 的类注释
        CameraStamp.render(event.getGuiGraphics(), mc.font, w, h);

        // 拍照期间必须跳过取景框，否则会被拍进照片
        if (CameraMode.suppressOverlay()) {
            CameraMode.markCleanFrame();
            return;
        }

        CameraOverlay.render(
                event.getGuiGraphics(),
                mc.font,
                w,
                h,
                System.currentTimeMillis(),
                // 模糊后处理要它来插值。false ＝ 不算暂停时的那一份，相机模式下
                // 游戏本来就没暂停，两者一样，取跟着游戏时间的那个更合语义
                event.getPartialTick().getGameTimeDeltaPartialTick(false));
    }

    /** 安全网：打开任意界面就退出相机模式，否则玩家会卡在没有 HUD 的状态里 */
    public static void onScreenOpening(ScreenEvent.Opening event) {
        CameraMode.exit();
    }
}
