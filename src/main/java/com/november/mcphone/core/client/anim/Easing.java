package com.november.mcphone.core.client.anim;

/**
 * 缓动函数集合。全部为纯函数：输入 0..1 进度，输出 0..1（个别 overshoot 曲线可略超）。
 *
 * 从 AtomChat 0.1.8 移植（MIT），原文件 {@code render/Easing.java}。
 * 与 vanilla GUI 无关，任何界面都能直接调用。
 */
public final class Easing {
    private Easing() {
    }

    public static float linear(float t) {
        return t;
    }

    public static float easeOutQuart(float t) {
        t = 1.0F - t;
        return 1.0F - t * t * t * t;
    }

    public static float easeOutBack(float t) {
        float c1 = 1.70158F;
        float c3 = c1 + 1.0F;
        return 1.0F + c3 * (float) Math.pow(t - 1.0F, 3) + c1 * (float) Math.pow(t - 1.0F, 2);
    }

    public static float easeOutCubic(float t) {
        t = 1.0F - t;
        return 1.0F - t * t * t;
    }

    /** 元素在屏幕上滑到新位置时用的标准 ease-in-out。 */
    public static float easeInOutCubic(float t) {
        return t < 0.5F
                ? 4.0F * t * t * t
                : 1.0F - (float) Math.pow(-2.0F * t + 2.0F, 3.0F) / 2.0F;
    }

    /**
     * 比 easeOutCubic 更柔和：透明度需要让眼睛真的看到"在渐淡"，
     * cubic 在前半段就走完约 88% 的行程——所以 cubic 驱动的淡入读起来
     * 只是"在滑动"。透明度用这条。
     */
    public static float easeOutQuad(float t) {
        t = 1.0F - t;
        return 1.0F - t * t;
    }

    /** 指数减速（Tuui 的 EaseOutQuart）：长尾平滑，适合滚动。 */
    public static float easeOutExpo(float t) {
        return t >= 1.0F ? 1.0F : (float) (1.0 - Math.pow(2.0, -10.0 * t));
    }
}
