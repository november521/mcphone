package com.november.mcphone.core.client;

import net.minecraft.util.Mth;

/**
 * 手机界面开多大 —— 玩家自己定的那个倍数。
 *
 * 为什么需要它
 *
 * 手机的每一处尺寸都是按 120×200 这个屏幕写死的（{@link PhoneTheme}），画出来是
 * GUI 单位 1:1。这在 1080p、GUI 缩放 3 上正好，但换到 4K + GUI 缩放 2 就成了一块
 * 邮票——而 GUI 缩放是【全局】设置，为看清手机把它调大，聊天框和物品栏跟着一起变大。
 * 所以这一档得是手机自己的。
 *
 * 它不是"改布局"，是【整体缩放】
 *
 * 界面里没有一个数需要跟着变：渲染时把整个手机套进一层 pose 缩放，鼠标坐标反过来
 * 除掉同一个倍数。各页照旧按 120×200 算自己的行高和命中，一行代码都不用改。
 * 代价是非整数倍时字会稍软——字体是位图，2.5 倍下每个字模跨不满整数个屏幕像素。
 * 所以步进给的是 25%，而不是 1%：让"整数倍"落得到（GUI 缩放 2 配 150% 正好是 3 倍）。
 *
 * 存哪儿
 *
 * 客户端配置（{@link ClientConfig#UI_SCALE}），跟着这台电脑走——它描述的是"这块屏幕
 * 上多大合适"，与存档、与服务器都没关系，和字体颜色同一个道理。存的是整数百分比而
 * 不是浮点：配置文件里 uiScale = 150 比 1.5000000596 好读，玩家手改时尤其。
 */
public final class PhoneScale {

    private PhoneScale() {}

    /** 最小 75%。再小字就开始糊成一团，那时候放大手机反而不如放大 GUI 缩放 */
    public static final int MIN_PERCENT = 75;

    /** 最大 300%。再大在 1080p 上已经顶到窗口，实际会被 {@link #fit} 夹回去 */
    public static final int MAX_PERCENT = 300;

    /** 加减一次走多少。25 是为了让整数倍落得到，理由见类注释 */
    public static final int STEP_PERCENT = 25;

    public static final int DEFAULT_PERCENT = 100;

    private static int percent = DEFAULT_PERCENT;

    /**
     * 上一次真的写进配置的值。
     *
     * 拖那条时每动一下都会改 percent，而落盘是要写文件的——拖一次能写几十次。
     * 所以拖动只改这个类里的数（下一帧就生效），松手才落一次盘，见 {@link #commit()}。
     */
    private static int savedPercent = DEFAULT_PERCENT;

    /**
     * 只用"清晰的倍数"。
     *
     * 手机是套在原版那个投影上放大的，所以字模的每个纹素最终占
     * {@code guiScale × 我们的倍数} 个物理像素——文字用的是 NEAREST 过滤，这个乘积
     * 是整数才横平竖直，不是整数就有的列占两像素、有的占三像素，细线时粗时细。
     *
     * 开着的时候，可选的档位对齐到 {@code 1/guiScale}：GUI 缩放 2 → 100/150/200…，
     * 缩放 3 → 100/133/166…，缩放 4 → 100/125/150…。这等于把原版"只能整数 GUI 缩放"
     * 那条路的唯一好处拿了过来，又不必去动 Window 的全局缩放。
     */
    private static boolean snap = true;

    public static int percent() {
        return percent;
    }

    /** 渲染要的那个倍数 */
    public static float get() {
        return percent / 100.0F;
    }

    /** 配置读进来时推给这里。渲染每帧都要问，不能去碰配置 */
    static void load(int value) {
        percent = clamp(value);
        savedPercent = percent;
    }

    static void loadSnap(boolean value) {
        snap = value;
    }

    public static boolean snapEnabled() {
        return snap;
    }

    public static void setSnap(boolean value) {
        if (snap == value) return;
        snap = value;
        ClientConfig.saveUiScaleSnap(value);
        // 打开时把当前值也对齐一下，否则开关写着"贴合"而屏幕上还是那个不整的倍数
        if (snap) setPercent(percent);
    }

    /** 一档"清晰"是多少百分比：guiScale 2 → 50，3 → 33.3，4 → 25 */
    public static double crispStepPercent() {
        double gui = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale();
        return gui > 0 ? 100.0 / gui : 100.0;
    }

    /**
     * 把一个百分比对齐到最近的清晰档。关掉贴合时原样返回。
     *
     * 两端要单独处理：对齐之后可能掉到 75 以下或 300 以上，那时候取范围内最靠边的
     * 那个【清晰档】，而不是直接夹成 75 或 300——夹出来的数正好是不清晰的。
     */
    public static int snapPercent(int value) {
        if (!snap) return clamp(value);

        double step = crispStepPercent();
        if (step <= 0) return clamp(value);

        int snapped = (int) Math.round(Math.round(value / step) * step);
        if (snapped < MIN_PERCENT) snapped = (int) Math.round(Math.ceil(MIN_PERCENT / step) * step);
        if (snapped > MAX_PERCENT) snapped = (int) Math.round(Math.floor(MAX_PERCENT / step) * step);
        return clamp(snapped);
    }

    /** 改一个值并立刻落盘。点加减键、点还原走这条 */
    public static void setPercent(int value) {
        preview(value);
        commit();
    }

    /** 只改不落盘。拖那条时每一步走这条，几十次拖动只对应一次写盘 */
    public static void preview(int value) {
        percent = snapPercent(value);
    }

    /** 松手时把拖出来的值落盘。没变过就什么都不做，省一次写盘 */
    public static void commit() {
        if (percent == savedPercent) return;
        savedPercent = percent;
        ClientConfig.saveUiScale(percent);
    }

    /**
     * 加减键。开着贴合时走的是"往那个方向挪一个清晰档"，而不是加减 25%——
     * 后者在 GUI 缩放 2 下会出现"点了没反应"：125 对齐回 150 或 100，看着像点漏了。
     */
    public static void nudge(int deltaPercent) {
        if (!snap) {
            setPercent(percent + deltaPercent);
            return;
        }

        double step = crispStepPercent();
        int index = (int) Math.round(percent / step);
        int target = snapPercent((int) Math.round((index + (deltaPercent > 0 ? 1 : -1)) * step));

        // 方向不能反。手改过配置、或者刚换过 GUI 缩放时，当前值可能不在任何一个清晰档上，
        // 对齐之后有可能落到当前值的另一侧——那时候按「−」会变大，玩家只会以为是坏了。
        // 这一下就不动：这种情况只有一种，就是当前值已经在这个方向的尽头之外
        // （例如 GUI 缩放 2 时停在 80%，比最小的那个清晰档 100% 还小，再往下没有档了）
        if (deltaPercent > 0 && target < percent) return;
        if (deltaPercent < 0 && target > percent) return;

        setPercent(target);
    }

    public static int clamp(int value) {
        return Mth.clamp(value, MIN_PERCENT, MAX_PERCENT);
    }

    /**
     * 窗口放得下多大 —— 手机连边框一起，不能比窗口还高还宽。
     *
     * 夹在这里而不是夹在设置里：窗口是随时会变的（拖窗口、切全屏、改 GUI 缩放），
     * 存进配置的那个数是玩家的意愿，不该被一次临时的小窗口永久改小。
     */
    public static float fit(int windowWidth, int windowHeight) {
        float byWidth = (float) windowWidth / PhoneTheme.PHONE_TOTAL_WIDTH;
        float byHeight = (float) windowHeight / PhoneTheme.PHONE_TOTAL_HEIGHT;
        return Math.max(0.1F, Math.min(byWidth, byHeight));
    }

    /** 这一帧真正用的倍数：玩家要的，与窗口放得下的，取小 */
    public static float effective(int windowWidth, int windowHeight) {
        return Math.min(get(), fit(windowWidth, windowHeight));
    }

    /** 现在是不是被窗口夹着——设置页据此说一句"窗口放不下" */
    public static boolean clampedByWindow(int windowWidth, int windowHeight) {
        return fit(windowWidth, windowHeight) < get() - 0.001F;
    }
}
