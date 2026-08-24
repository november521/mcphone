package com.november.mcphone.api.client.ui;

/**
 * 手机当前的配色 —— 让附属的页面长得像手机里的东西，而不是外来户。
 *
 * 为什么非开放不可
 *
 * 不给的话，写附属页面的人只有两条路：照着截图取色写死，或者随便挑几个颜色。
 * 前者在我们调整配色时会变得不协调，后者从第一天起就不协调——而玩家看到的是
 * 同一部手机里两种画风，只会觉得这模组做得糙。
 *
 * 这是"开放"的一部分：能装上不等于能融进去。
 *
 * 为什么是接口而不是一堆 public static final int
 *
 * 常量会被编译器内联进调用方的 class 文件。附属编译时手机是深色的，之后我们
 * 换了配色，附属那边的字面量还是旧值——它拿到的是编译那天的颜色，而且没有任何
 * 迹象表明哪里不对。
 *
 * 走接口就是一次真实调用，永远拿到当下的值。这也给将来的主题切换留了门：那时
 * 只是换一个实现，附属一行都不用改。
 *
 * 颜色格式
 *
 * 全部是 ARGB（0xAARRGGBB），可以直接传给 GuiGraphics 的 fill 与 drawString。
 */
public interface PhoneStyle {

    /** 标题、当前选中项。最亮的那一档 */
    int titleColor();

    /** 正文。绝大多数文字用这个 */
    int bodyColor();

    /** 次要信息：说明、时间戳、占位提示。比正文暗一档 */
    int subtleColor();

    /** 强调色，用来标价格、数字、需要一眼看到的东西 */
    int accentColor();

    /** 手机屏幕底色。整页铺底时用它，别自己挑一个深色 */
    int screenBackground();

    /** 悬停或按下时垫在下面的那层。半透明，直接盖在内容上 */
    int pressedOverlay();

    /** 可点按钮的底色 */
    int buttonColor();

    /** 鼠标悬在按钮上时的底色 */
    int buttonHoverColor();

    /** 点不动的按钮底色。灰掉的那种 */
    int buttonDisabledColor();

    /** 点不动的按钮上的文字颜色 */
    int buttonDisabledTextColor();
}
