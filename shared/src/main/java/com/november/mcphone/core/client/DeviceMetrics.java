package com.november.mcphone.core.client;

import com.november.mcphone.core.DeviceKind;
import org.jetbrains.annotations.Nullable;

/**
 * 一台设备的屏幕有多大 —— 手机竖着 120×200，平板横着 240×168。
 *
 * <h2>为什么要有这个类</h2>
 *
 * 在它之前，屏幕尺寸是 {@link PhoneTheme} 里的两个常量，各处直接引用。这在只有手机时
 * 没问题；有了平板就不行了 —— 同一份渲染代码这一帧画的是 120 宽，下一帧可能是 240 宽，
 * 而常量是编译期的东西，改不了。
 *
 * 挪进一个对象之后，"这一台多大"由界面自己拿着（{@link PhoneScreen#metrics}），一路
 * 传给机身、主屏与各页。<b>各页早就是按传进来的宽高排版的</b>（它们的 render 收
 * {@code screenW / screenH}，附属那一侧收 {@link com.november.mcphone.api.client.ui.PhoneCanvas}），
 * 所以真正要改的只有"谁来给这两个数"这一层，页面一行都不用动。
 *
 * <h2>不含 Minecraft 类型</h2>
 *
 * 与 {@link HomeLayout} 同一个理由：纯算术能用 javac 直接编出来跑断言，不必开游戏。
 * 见 {@code docs/DeviceLayoutTest}。往里加东西时别破坏这一条。
 *
 * @param screenW 屏幕内区域宽（不含边框）
 * @param screenH 屏幕内区域高（不含边框）
 */
public record DeviceMetrics(int screenW, int screenH) {

    /** 手机：竖屏 120×200，所有页面最初就是照着它排的版 */
    public static final DeviceMetrics PHONE = new DeviceMetrics(120, 200);

    /**
     * 平板：横屏 240×168。
     *
     * 宽正好是手机的两倍，高约 0.7 倍 —— 长宽比 1.43 跟着物品模型走（机身设计尺寸
     * 248×174），拿在手上看到的和打开后看到的才是同一台机器。
     *
     * 含边框 256×184，比原版保证的最小逻辑窗口（320×240）还小一圈，所以任何分辨率下
     * 都摆得开；真放不下时 {@link PhoneScale#fit} 还会再缩一道。
     */
    public static final DeviceMetrics TABLET = new DeviceMetrics(240, 168);

    /**
     * 这种设备的屏幕。
     *
     * {@code null} 按手机算 —— 传进来的是 {@code PhoneItem.kindOf} 的结果，位置失效
     * （手机被丢了、格子空了）时它是 null。那一帧界面多半正要关掉，但在关掉之前
     * 仍然要有一套尺寸能画，退回手机那套是无害的一侧。
     */
    public static DeviceMetrics of(@Nullable DeviceKind kind) {
        return kind == DeviceKind.TABLET ? TABLET : PHONE;
    }

    /** 机身宽（含两侧边框）。摆位置、判点击在不在机身内用它 */
    public int totalWidth() {
        return screenW + PhoneTheme.PHONE_BORDER * 2;
    }

    /** 机身高（含上下边框） */
    public int totalHeight() {
        return screenH + PhoneTheme.PHONE_BORDER * 2;
    }

    /**
     * 导航条那一条有多厚。
     *
     * 竖屏时它是"高"，横屏时它是"宽" —— 同一个数，条只是立了起来。厚薄由图标的设计高度
     * 定（{@link PhoneTheme#NAV_BAR_HEIGHT}），跟屏幕多大没关系。
     */
    public int navThickness() {
        return PhoneTheme.NAV_BAR_HEIGHT;
    }

    /**
     * 导航条立在右边（横屏），还是横在底下（竖屏）。
     *
     * 为什么横屏要挪走：三个键横在底下时，它们占的是<b>最缺的那一维</b>。平板高只有 168，
     * 底下再切掉 14，页面能用的高度比手机还矮；而宽度有 240，挪到右边切掉的 14 几乎看不出来。
     * 真平板也是这么摆的。
     */
    public boolean sideNav() {
        return landscape();
    }

    /** 屏幕底下被导航条占掉多少 —— 横屏是 0，那条挪去了右边 */
    public int bottomNavHeight() {
        return sideNav() ? 0 : navThickness();
    }

    /**
     * 内容区宽度 —— 页面能用的就这么宽。
     *
     * 竖屏是整块屏幕；横屏要扣掉右边那条导航条，否则页面会画到它底下去。
     */
    public int contentWidth() {
        return screenW - (sideNav() ? navThickness() : 0);
    }

    /** 内容区高度 —— 扣掉顶上的状态栏，以及底下的导航条（横屏时它不在底下，扣 0） */
    public int contentHeight() {
        return screenH - PhoneTheme.STATUS_BAR_HEIGHT - bottomNavHeight();
    }

    /** 横屏。页面想按屏幕形状换排布时问它，别去比 {@link #screenW} 是不是 240 */
    public boolean landscape() {
        return screenW > screenH;
    }
}
