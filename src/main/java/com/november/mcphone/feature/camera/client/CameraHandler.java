package com.november.mcphone.feature.camera.client;

import com.november.mcphone.core.client.MCphoneKeyBindings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;

/**
 * 相机模式的事件监听。五个事件都在游戏总线（MinecraftForge.EVENT_BUS），由 MCphoneClient 显式 addListener；
 * 按键的注册在模组总线，见 MCphoneKeyBindings。拍照的分帧时序见 {@link CameraMode} 的类注释。
 *
 * 比 NeoForge 那一支多两个监听器，原因在 hideGui
 *
 * 那边只要三个：tick、RenderGuiEvent、ScreenEvent。相机模式把 hideGui 置 true
 * 藏掉 HUD，而 NeoForge 的 RenderGuiEvent 不受 hideGui 影响，取景框照画。
 *
 * Forge 1.20.1 上 RenderGuiEvent 是 ForgeGui 在 gui.render 里派发的，而
 * GameRenderer 写的是 {@code if (!hideGui || screen != null) gui.render(...)}
 * —— hideGui 一真，事件根本不发，取景框跟 HUD 一起消失。玩家报的
 * "F1 会把框也省略掉"就是这个。
 *
 * 所以这一支把两件事拆开做：
 *
 *   藏 HUD    逐个取消 RenderGuiOverlayEvent（26 个原版 overlay 全在内）
 *   画取景框  hideGui 强制为 false，让 RenderGuiEvent 照常派发
 *
 * 拍照那几帧再把 hideGui 临时打开 —— 那时候连 toast 都要一起藏掉，
 * 而 toast 不走 overlay 体系，只认 hideGui。详见下面两个 RenderTick 监听器。
 */
public final class CameraHandler {

    private CameraHandler() {}

    // 1.20.1 只有一个 TickEvent.ClientTickEvent，Pre 与 Post 都从这儿进来，
    // 不判 phase 会一 tick 触发两次。Post 对应 Phase.END
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
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

    /**
     * 每帧开画之前定这一帧藏什么。
     *
     * 【必须在 RenderTickEvent.START 上做，不能挪进 20Hz 的 clientTick】：
     * 玩家在相机模式里按 F1 时，hideGui 会被原版翻成 true，下一帧取景框就没了。
     * 帧率通常高于 tick 率，用 clientTick 兜的话中间会闪几帧。
     *
     * 字节码里 Minecraft.runTick 的顺序是
     * onRenderTickStart → GameRenderer.render → onRenderTickEnd，
     * 所以这里改的值当帧就生效。
     *
     * 取值只有两种：
     *   平时     false —— 让 gui.render 跑起来，RenderGuiEvent 才会派发，
     *                     取景框画得出来；HUD 由 overlay 那个监听器藏
     *   拍照期间 true  —— 整条 HUD 链路连同 toast 一起跳过。toast 不走
     *                     overlay 体系、只认 hideGui，不这么做就可能有个
     *                     成就弹窗被拍进照片
     */
    public static void onRenderTickStart(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!CameraMode.isActive()) return;

        Minecraft.getInstance().options.hideGui = CameraMode.suppressOverlay();
    }

    /**
     * 一帧画完了。拍照期间这意味着"刚刚过去的这一帧是干净的"。
     *
     * 【这个判定必须放在这里，不能留在 RenderGuiEvent 里】：拍照那几帧
     * hideGui 是 true，gui.render 整个被跳过，RenderGuiEvent 压根不派发 ——
     * 留在那边的话 markCleanFrame 永远等不到，快门按下去就再也不响。
     *
     * 放在帧尾还更准：那边判的是"这一帧没画取景框"，这边判的是
     * "这一整帧都渲染完了且没画取景框"。
     */
    public static void onRenderTickEnd(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!CameraMode.isActive()) return;
        if (!CameraMode.suppressOverlay()) return;

        CameraMode.markCleanFrame();
    }

    /**
     * 藏掉原版 HUD。
     *
     * 相机模式期间把每一个原版 overlay 都取消掉 —— VanillaGuiOverlay 那 26 项
     * （准星、物品栏、血条、聊天、记分板……）全在内，效果与 hideGui 等价，
     * 但【不会连我们自己的取景框一起藏掉】，因为取景框走的是 RenderGuiEvent.Post，
     * 在所有 overlay 之后。
     */
    public static void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (CameraMode.isActive()) event.setCanceled(true);
    }

    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!CameraMode.isActive()) return;

        // 拍照期间不画取景框，否则会被拍进照片。
        // （此时 hideGui 已被置真，本方法其实根本不会被调到，这一行是双保险）
        if (CameraMode.suppressOverlay()) return;

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
