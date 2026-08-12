package com.november.mcphone.client;

import net.minecraft.client.Minecraft;

/**
 * 相机模式状态机（纯客户端）。
 *
 * 相机不是一个 GUI 界面，而是一个覆盖在游戏画面上的模式：
 * 进入后手机界面关闭，玩家可以正常走动和转视角，屏幕上叠加取景框。
 *
 * ================================================================
 * 拍照时序 —— 为什么不能直接抓取
 * ================================================================
 *
 * Screenshot.grab 抓的是整个 framebuffer，取景框如果正画在屏幕上，
 * 就会被一起拍进照片里。所以拍照必须分几步：
 *
 *   1. 按下拍照键（tick）        pendingCapture = true
 *   2. 渲染帧                    检测到 pendingCapture 就跳过取景框绘制，
 *                                并置 cleanFrameReady = true
 *   3. 下一个 tick               抓取上一帧（此时画面里没有取景框）
 *
 * pendingCapture 会一直抑制取景框直到抓取完成。tick 是 20/s 而渲染
 * 通常是 60/s，两步之间会插入多帧，但那些帧同样被抑制，因此 tick 中
 * 抓到的必然是干净画面。
 *
 * 抓取放在 tick 而非渲染过程中，是因为 Screenshot.grab 内部会
 * bindTexture 操作 RenderSystem，在渲染中途调用有干扰渲染状态的风险。
 * 放在 tick 里与原版 F2 的调用时机完全一致。
 */
public final class CameraMode {

    private static boolean active = false;

    /** 进入相机前玩家原本的 hideGui 设置，退出时还原 */
    private static boolean savedHideGui = false;

    private static long enteredAtMs = 0L;

    // ---- 拍照流程 ----
    private static boolean pendingCapture = false;
    private static boolean cleanFrameReady = false;

    /** 最近一次拍照完成的时刻，用于白色闪光效果 */
    private static long flashAtMs = 0L;

    private CameraMode() {}

    // ============================================================
    //  进入 / 退出
    // ============================================================

    public static boolean isActive() { return active; }

    public static void enter() {
        if (active) return;
        Minecraft mc = Minecraft.getInstance();

        active = true;
        savedHideGui = mc.options.hideGui;
        mc.options.hideGui = true;   // 等效原版 F1，藏掉准星与物品栏
        enteredAtMs = System.currentTimeMillis();
        pendingCapture = false;
        cleanFrameReady = false;
    }

    public static void exit() {
        if (!active) return;

        // 还原玩家原本的设置，而不是无脑置 false——
        // 玩家可能本来就自己按了 F1
        Minecraft.getInstance().options.hideGui = savedHideGui;
        active = false;
        pendingCapture = false;
        cleanFrameReady = false;
    }

    // ============================================================
    //  拍照流程
    // ============================================================

    /** 按下拍照键时调用 */
    public static void requestCapture() {
        if (!active || pendingCapture) return;
        pendingCapture = true;
        cleanFrameReady = false;
    }

    /** 取景框此刻是否应被抑制（拍照期间不能入镜） */
    public static boolean suppressOverlay() { return pendingCapture; }

    /** 由渲染层调用，告知本帧未绘制取景框 */
    public static void markCleanFrame() {
        if (pendingCapture) cleanFrameReady = true;
    }

    /** 是否已经有一帧干净画面可供抓取 */
    public static boolean shouldGrabNow() { return pendingCapture && cleanFrameReady; }

    /** 抓取完成后调用，恢复取景框并触发白闪 */
    public static void finishCapture() {
        pendingCapture = false;
        cleanFrameReady = false;
        flashAtMs = System.currentTimeMillis();
    }

    // ============================================================
    //  供渲染层读取
    // ============================================================

    public static long getEnteredAtMs() { return enteredAtMs; }
    public static long getFlashAtMs() { return flashAtMs; }
}
