package com.november.mcphone.feature.camera.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.AppOptions;
import com.november.mcphone.core.client.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
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
 * 借原版那支模糊着色器（{@code shaders/program/blur}），自己拿一条后处理链跑它。
 * 半径跟着时间收，模糊是【化开又收回去】的，不是硬切一块糊的画面。
 *
 * 这一支为什么要自己拼这条链
 *
 * 1.21.1 那边直接 new 一份原版的 {@code shaders/post/blur.json}，再靠
 * {@code PostChain.setUniform} 每帧改半径。1.20.1 上【没有 setUniform】——
 * 那是 1.21 才加的，这个版本的 PostChain 只在加载 json 时读一次 uniform，
 * 之后就再没有公开的口子能改（{@code passes} 是私有字段）。
 *
 * 于是换个方向：自带的 {@code mcphone:shaders/post/camera_blur.json} 里【只声明那张
 * swap 中转贴图、不声明任何 pass】，两个 pass 由代码调 {@code addPass} 加进去——
 * 它把创建出来的 {@link PostPass} 还给我们，拿着它就能每帧
 * {@code getEffect().safeGetUniform("Radius")}。着色器程序仍然是原版那支，
 * 与那一支画出来的是同一个效果；两个 pass 的走向（横一遍、竖一遍，主目标 → swap →
 * 主目标）也照抄原版 blur.json。
 *
 * 时序：闪光从 {@link CameraMode#finishCapture()} 起算，而那一句发生在
 * {@code Screenshot.grab} 之前一行——抓的是上一帧已经画完的干净画面，所以
 * 无论白闪还是模糊都【不会进照片】。
 */
public final class CameraFlash {

    private CameraFlash() {}

    /** 闪一下多久，毫秒。两种效果共用：换个样子而已，节奏该是一样的 */
    public static final int FLASH_MS = 220;

    /**
     * 最狠的那一帧用多大半径。
     *
     * 1.21.1 那边写的是 {@code GameRenderer.MAX_BLUR_RADIUS}，1.20.1 上没有这个常量。
     * 这里的 10 就是它的值（对着 1.21.1 的 GameRenderer 核过），两支糊出来的一样狠。
     */
    private static final float MAX_RADIUS = 10.0F;

    /** 自带的那份链定义：只有一张 swap 中转贴图，两个 pass 在代码里加，见类注释 */
    private static final ResourceLocation BLUR =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "shaders/post/camera_blur.json");

    /** 原版那支模糊着色器的名字。{@code shaders/program/blur.json}，我们不自带 */
    private static final String BLUR_PROGRAM = "blur";

    /** 中转贴图的名字，与上面那份 json 里写的必须一致 */
    private static final String SWAP_TARGET = "swap";

    /** true = 模糊，false = 白闪。值的真身在配置里，这里是渲染每帧要读的那一份 */
    private static boolean soft = false;

    private static PostChain chain;
    /** 横、竖两遍。留着引用是为了每帧改半径——链本身给不出它的 pass */
    private static PostPass passH, passV;
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
        float radius = 1.0F + (MAX_RADIUS - 1.0F) * t;
        passH.getEffect().safeGetUniform("Radius").set(radius);
        passV.getEffect().safeGetUniform("Radius").set(radius);
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
        // 两个 pass 是链的一部分，链关掉它们就跟着废了。不清空的话下一次
        // renderBlur 会往一对已经 close 过的着色器上写 uniform
        passH = null;
        passV = null;
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

                // 两遍高斯：先横后竖，中间过一张与屏幕同尺寸的 swap。
                // 走向与原版 blur.json 逐字相同，只是这里由代码加而不是由 json 声明
                var swap = chain.getTempTarget(SWAP_TARGET);
                // 取不到只有一个可能：资源包换掉了我们那份 json 又删了 targets。
                // 不拦的话崩在 addPass 里面，报的是一句与 swap 毫无关系的 NPE
                if (swap == null) {
                    throw new IllegalStateException(
                            "后处理链里没有名为 " + SWAP_TARGET + " 的中转贴图");
                }
                passH = chain.addPass(BLUR_PROGRAM, target, swap);
                passV = chain.addPass(BLUR_PROGRAM, swap, target);

                // 方向只设一次；每帧要改的只有半径
                passH.getEffect().safeGetUniform("BlurDir").set(1.0F, 0.0F);
                passV.getEffect().safeGetUniform("BlurDir").set(0.0F, 1.0F);
            } catch (Exception e) {
                chainFailed = true;
                // 链建起来了、加 pass 时才炸的那一路：它已经占着几张与屏幕同尺寸的
                // 贴图，不关就是一路漏到退出游戏
                if (chain != null) chain.close();
                chain = null;
                passH = null;
                passV = null;
                MCphone.LOGGER.warn("[MCphone] 相机的模糊闪光建不起来，这次改用白闪: {}", e.toString());
                return null;
            }
            // 必须在加完 pass 之后：resize 顺带把正交矩阵发给每个 pass，
            // 漏了这一步 process 那一刻会拿着一个 null 矩阵
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
