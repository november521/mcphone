package com.november.mcphone.platform.client.port;

/** 旧版全局贴图调色转为新版绘制指令自身的 ARGB，避免延迟渲染时丢失颜色。 */
public final class PhoneTextureState {
    private static int color = -1;
    private PhoneTextureState() {}
    private static int channel(float value) { return Math.max(0,Math.min(255,(int)(value*255))); }
    public static int color() { return color; }
    public static void setShaderColor(float r,float g,float b,float a) {
        color=channel(a)<<24|channel(r)<<16|channel(g)<<8|channel(b);
    }
    // 26.1 使用 GUI_TEXTURED 管线自带的透明混合，不再操作全局 GL 混合开关。
    public static void enableBlend() {}
    public static void defaultBlendFunc() {}
    public static void disableBlend() {}
}
