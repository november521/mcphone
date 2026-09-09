package com.november.mcphone.feature.camera.client;

import com.november.mcphone.core.client.PhoneKeys;
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
 * 拍照那几帧也不动 hideGui 的这个安排 —— 那时候连 toast 都要一起藏掉，而 toast 不走
 * overlay 体系、只认 hideGui。办法是把翻它的那一下【推迟到 gui.render 之后】：toast
 * 自己读 hideGui 的时刻在那之后，于是印记先画进这一帧，toast 再闭嘴。见 onRenderGui。
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
            // 坐标写进文件名：相册那一行读的就是它（见 CameraStamp.coordsIn）。
            // 这一支还画不了烧进像素的那一半，理由见本类注释里 hideGui 那一段
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
     * 【一律 false，拍照那几帧也是】。gui.render 必须跑起来 —— RenderGuiEvent 是
     * ForgeGui 在它里头派发的，而 GameRenderer 那句 {@code if (!hideGui || screen != null)}
     * 一旦挡住它，我们这一帧什么都画不成，照片印记也跟着没有。
     *
     * 拍照期间要藏的 toast 另有办法：它不走 overlay 体系、只认 hideGui，但它自己读
     * hideGui 的时刻在 gui.render 【之后】（ToastComponent.render 的第一句）。所以把
     * 那一下推迟到 {@link #onRenderGui} 的末尾，先画完印记再翻 —— 见那边的注释。
     */
    public static void onRenderTickStart(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!CameraMode.isActive()) return;

        Minecraft.getInstance().options.hideGui = false;
    }

    /**
     * 一帧画完了。拍照期间这意味着"刚刚过去的这一帧是干净的"。
     *
     * 【放在帧尾而不是 RenderGuiEvent 里】：那边判的是"这一帧没画取景框"，这边判的是
     * "这一整帧都渲染完了且没画取景框"。抓取读的是整个主渲染目标，判据该跟着那个范围走。
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

        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        // 印记是相机里【唯一】允许入镜的一层，所以画在取景框那道 return 之前 ——
        // 要被抓的正是这一帧，见 CameraStamp 的类注释
        CameraStamp.render(event.getGuiGraphics(), mc.font, w, h);

        if (CameraMode.suppressOverlay()) {
            // 拍照期间不画取景框，否则会被拍进照片。
            //
            // 【hideGui 在这一句翻真，不在帧首】：toast 只认 hideGui，而它读这个值
            // 是在本帧稍后（ToastComponent.render 的第一句），gui.render 已经跑完。
            // 于是这一帧的顺序成了：印记画上 → 这里翻真 → toast 自己闭嘴 → 抓取。
            // 帧首翻真的话 gui.render 整个被跳过，连印记都画不上。
            // 帧首那个监听器下一帧会把它翻回 false
            mc.options.hideGui = true;
            return;
        }

        CameraOverlay.render(
                event.getGuiGraphics(),
                mc.font,
                w,
                h,
                System.currentTimeMillis(),
                // 模糊后处理要它来插值。1.21.1 那边这个 getter 给的是 DeltaTracker，
                // 还要再问它要一个 getGameTimeDeltaPartialTick(false)；1.20.1 上
                // 它本身就是一个 float，取到的是同一个数
                event.getPartialTick());
    }

    /** 安全网：打开任意界面就退出相机模式，否则玩家会卡在没有 HUD 的状态里 */
    public static void onScreenOpening(ScreenEvent.Opening event) {
        CameraMode.exit();
    }
}
