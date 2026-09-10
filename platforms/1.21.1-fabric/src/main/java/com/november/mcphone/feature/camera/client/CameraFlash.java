package com.november.mcphone.feature.camera.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.AppOptions;
import com.november.mcphone.core.client.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
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
 * 借原版菜单背景那条模糊后处理链（{@code shaders/post/blur.json}），自己 new 一份，
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
            ResourceLocation.withDefaultNamespace("shaders/post/blur.json");

    /** true = 模糊，false = 白闪。值的真身在配置里，这里是渲染每帧要读的那一份 */
    private static boolean soft = false;

    private static PostChain chain;
    private static int chainW, chainH;

    /** 建失败过就不再试。每帧重试一次只会把日志刷爆，而且照样没有模糊 */
    private static boolean chainFailed = false;

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
        if (!soft) return;

        float t = progress(nowMs);
        if (t <= 0.0F) return;

        Minecraft mc = Minecraft.getInstance();
        PostChain blur = chain(mc);
        if (blur == null) return;

        // GuiGraphics 是攒一批再画的。不 flush 的话，这一帧的 HUD 还没落到目标上，
        // 模糊处理的就是上一帧的内容，快门那一下会看起来慢半拍
        g.flush();

        // 半径跟着淡出往下收。收到 1 以下原版那个着色器等于没模糊，所以下限取 1
        blur.setUniform("Radius", 1.0F + (MAX_RADIUS - 1.0F) * t);
        blur.process(partialTick);

        // 后处理链走完，主目标不再是当前写入目标。不绑回来的话，这之后画的取景框
        // 会落到链里那张临时纹理上——屏幕上什么都看不见，也不报错
        mc.getMainRenderTarget().bindWrite(false);
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
        if (chain != null) {
            chain.close();
            chain = null;
        }
        // 重载之后允许再试一次：上次失败可能就是因为资源包里缺东西，而它刚被换掉
        chainFailed = false;
    }

    /** 这一刻闪到哪儿了：1 是刚按下快门，0 是结束。不在闪光期内返回 0 */
    private static float progress(long nowMs) {
        long since = nowMs - CameraMode.getFlashAtMs();
        if (since < 0 || since >= FLASH_MS) return 0.0F;
        return 1.0F - (float) since / FLASH_MS;
    }

    /** 拿到那条链，顺带跟着窗口尺寸走。建不出来就返回 null，调用方当作没有模糊 */
    private static PostChain chain(Minecraft mc) {
        var target = mc.getMainRenderTarget();

        if (chain == null) {
            if (chainFailed) return null;
            try {
                chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), target, BLUR);
            } catch (Exception e) {
                chainFailed = true;
                MCphone.LOGGER.warn("[MCphone] 相机的模糊闪光建不起来，这次改用白闪: {}", e.toString());
                return null;
            }
            chain.resize(target.width, target.height);
            chainW = target.width;
            chainH = target.height;
            return chain;
        }

        // 窗口大小变了要跟着改，否则模糊出来的是拉伸错位的画面
        if (chainW != target.width || chainH != target.height) {
            chain.resize(target.width, target.height);
            chainW = target.width;
            chainH = target.height;
        }
        return chain;
    }
}
