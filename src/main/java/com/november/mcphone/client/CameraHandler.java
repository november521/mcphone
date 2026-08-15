package com.november.mcphone.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import com.november.mcphone.core.client.MCphoneKeyBindings;

/**
 * 相机模式的游戏总线监听。
 *
 * ================================================================
 * 总线
 * ================================================================
 *
 * 这里的三个事件都在【游戏总线】(NeoForge.EVENT_BUS)，由
 * MCphoneClient 构造函数显式 addListener 挂载。
 * 按键的【注册】则在模组总线，见 MCphoneKeyBindings。
 *
 * ================================================================
 * 为什么截图在 tick 而不在渲染事件里
 * ================================================================
 *
 * Screenshot.grab 内部会 bindTexture 操作 RenderSystem，在渲染中途
 * 调用有干扰渲染状态的风险。放在 tick 中与原版 F2 的时机完全一致。
 * 拍照的分帧时序见 CameraMode 的类注释。
 */
public final class CameraHandler {

    private CameraHandler() {}

    // ============================================================
    //  按键与截图
    // ============================================================

    public static void onClientTick(ClientTickEvent.Post event) {
        if (!CameraMode.isActive()) return;

        Minecraft mc = Minecraft.getInstance();

        // 上一帧已经是不含取景框的干净画面，可以抓取了
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

    // ============================================================
    //  取景框渲染
    // ============================================================

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

    // ============================================================
    //  安全网
    // ============================================================

    /**
     * 打开任意界面（背包、暂停菜单等）时自动退出相机模式。
     * 否则玩家会卡在没有 HUD 的状态里，且不知道该按什么键恢复。
     */
    public static void onScreenOpening(ScreenEvent.Opening event) {
        CameraMode.exit();
    }
}
