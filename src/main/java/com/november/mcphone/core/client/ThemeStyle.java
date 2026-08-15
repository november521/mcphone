package com.november.mcphone.core.client;

import com.november.mcphone.api.client.ui.PhoneStyle;

/**
 * {@link PhoneStyle} 的实现 —— 把内部的 {@link PhoneTheme} 转成对外的只读配色。
 *
 * ================================================================
 * 为什么要隔这一层，不直接把 PhoneTheme 开放出去
 * ================================================================
 *
 * PhoneTheme 里除了颜色还有一大堆内部布局参数（图标尺寸、网格间距、状态栏
 * 高度……），那些是我们随时会调的实现细节。整个开放出去等于把它们全变成不能
 * 动的公开契约，以后连挪一个像素都要考虑会不会打断附属。
 *
 * 这一层挑出真正该让附属知道的那十项。将来做主题切换时，也只需要换掉这个类
 * 的实现，附属一行都不用改——那正是 PhoneStyle 做成接口而不是常量的原因。
 */
public final class ThemeStyle implements PhoneStyle {

    /** 只有一份配色，不必每帧新建 */
    public static final ThemeStyle INSTANCE = new ThemeStyle();

    private ThemeStyle() {}

    @Override public int titleColor() { return PhoneTheme.FONT_COLOR_TITLE; }

    @Override public int bodyColor() { return PhoneTheme.FONT_COLOR_BODY; }

    @Override public int subtleColor() { return PhoneTheme.FONT_COLOR_SUBTLE; }

    @Override public int accentColor() { return PhoneTheme.FONT_COLOR_PRICE; }

    @Override public int screenBackground() { return PhoneTheme.COLOR_SCREEN_BG; }

    @Override public int pressedOverlay() { return PhoneTheme.COLOR_APP_PRESSED; }

    @Override public int buttonColor() { return PhoneTheme.COLOR_BUTTON; }

    @Override public int buttonHoverColor() { return PhoneTheme.COLOR_BUTTON_HOVER; }

    @Override public int buttonDisabledColor() { return PhoneTheme.COLOR_BUTTON_DISABLED; }

    @Override public int buttonDisabledTextColor() { return PhoneTheme.FONT_COLOR_BUTTON_DISABLED; }
}
