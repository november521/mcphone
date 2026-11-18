package com.november.mcphone.feature.camera.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.AppOptions;
import com.november.mcphone.core.client.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import com.november.mcphone.platform.client.port.PhoneCameraBlur;
import net.minecraft.resources.ResourceLocation;

/**
 * 拍照那一下的反馈：默认是一片白闪，也可以换成【模糊一下】。
 *
 * 在「设置 → App 管理器 → 相机」里换，存在客户端配置里（跟着这台电脑走）。
 *
 * 为什么给这个选择
 *
 * 满屏白闪是照相机的老习惯，但它在游戏里有个实际的坏处：夜里或在暗处拍照时
 * 整个屏幕会被顶到全白，眼睛要缓好几秒。模糊那一版只是把画面糊一下再收回来，
 * 同样交代了"拍下来了"，但不改变亮度。
 *
 * 模糊是怎么做的
 *
 * 借原版菜单背景那条模糊后处理链（{@code post_effect/blur.json}），自己 new 一份，
 * 【不走】{@code GameRenderer.processBlurEffect}：那个方法的半径直接取玩家的
 * "菜单背景模糊度"设置，玩家把它调成 0 的话我们这里就会一声不响地什么都不做——
 * 开了开关却没有任何反应，是最难查的一类"坏了"。自己拿着这条链还多一样好处：
 * 半径可以跟着时间收，模糊是【化开又收回去】的，不是硬切一块糊的画面。
 *
 * 时序：闪光从 {@link CameraMode#finishCapture()} 起算，而那一句发生在
 * {@code Screenshot.grab} 之前一行——抓的是上一帧已经画完的干净画面，所以
 * 无论白闪还是模糊都【不会进照片】。
 */
public final class CameraFlash {

    private CameraFlash() {}

    /** 闪一下多久，毫秒。两种效果共用：换个样子而已，节奏该是一样的 */
    public static final int FLASH_MS = 220;

    /** 最狠的那一帧用多大半径。原版菜单背景模糊的上限就是这个数 */
    private static final float MAX_RADIUS = GameRenderer.MAX_BLUR_RADIUS;

    /** 原版那条链。我们只是再 new 一份，没有自带任何着色器文件 */
    private static final ResourceLocation BLUR =
            ResourceLocation.withDefaultNamespace("post_effect/blur.json");

    /** true = 模糊，false = 白闪。值的真身在配置里，这里是渲染每帧要读的那一份 */
    private static boolean soft = false;


    //  开关

    public static boolean isSoft() {
        return soft;
    }

    /** 配置读进来时推给这里。渲染只读这个静态字段，一帧都不碰配置 */
    public static void setSoft(boolean value) {
        soft = value;
    }

    /** App 管理器里那一行的定义。由 {@link CameraApp} 在构造时登记 */
    public static AppOptions.Toggle appOption() {
        return new AppOptions.Toggle(
                "mcphone.camera.flash",
                "mcphone.camera.flash_blur",
                "mcphone.camera.flash_white",
                CameraFlash::isSoft,
                value -> {
                    // 先让下一帧就用上，再落盘。存盘会绕回 ClientConfig.apply 再设一次
                    // 同样的值——重复但无害，与字体颜色那几项同一套路数
                    soft = value;
                    ClientConfig.saveCameraSoftFlash(value);
                });
    }

    //  渲染

    /**
     * 模糊那一版。要画在取景框【之前】：模糊的是已经画完的那部分画面，
     * 卡尺与准星得留在清楚的一层上，否则玩家会以为是自己眼花。
     */
    public static void renderBlur(GuiGraphics g, float partialTick, long nowMs) {
        float t = soft ? progress(nowMs) : 0;
        PhoneCameraBlur.request(g,t<=0?0:1.0F+(MAX_RADIUS-1.0F)*t);
    }

    /** 白闪那一版。画在最上面，盖住取景框才像"闪了一下" */
    public static void renderWhite(GuiGraphics g, int w, int h, long nowMs) {
        if (soft) return;

        float t = progress(nowMs);
        if (t <= 0.0F) return;

        g.fill(0, 0, w, h, ((int) (t * 200) << 24) | 0xFFFFFF);
    }

    /** 资源重载时扔掉这条链：着色器程序跟着资源走，留着旧的会画出黑屏且不报错 */
    public static void dispose() {
        PhoneCameraBlur.dispose();
    }

    /** 这一刻闪到哪儿了：1 是刚按下快门，0 是结束。不在闪光期内返回 0 */
    private static float progress(long nowMs) {
        long since = nowMs - CameraMode.getFlashAtMs();
        if (since < 0 || since >= FLASH_MS) return 0.0F;
        return 1.0F - (float) since / FLASH_MS;
    }

}
