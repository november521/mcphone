package com.november.mcphone.core.client;

import net.minecraft.util.Mth;

/**
 * 手机挂在 HUD 上的什么地方、多大。
 *
 * 为什么存的是「锚点 + 偏移」而不是一对坐标
 *
 * 存绝对坐标的话，玩家在 1080p 全屏下把手机摆到右下角，切成小窗口再进来，那对坐标
 * 已经落在窗口外面了——手机整个不见，玩家还以为功能坏了。改分辨率、改 GUI 缩放、
 * 拖窗口大小都会触发这件事，而这三件都是常事。
 *
 * 锚点解决的正是这个：记的是「贴着右下角、再往里挪 4 像素」，换多大的窗口都还在右下角。
 * 九个锚点用两个 0/1/2 的序号表示，位置就是一句
 * {@code col * (屏幕宽 - 手机宽) / 2}——0 贴左、1 居中、2 贴右，竖着同理。
 *
 * 偏移则允许玩家微调：贴着角落多半会压到原版的物品栏或经验条，往里挪几像素才好看。
 *
 * 为什么 HUD 的倍数与 {@link PhoneScale} 分开
 *
 * 那一档答的是「全屏打开时这块屏幕上多大合适」，起点是 100%（原样）。HUD 要答的是
 * 另一个问题：「一直挂在画面角落里，多大才不挡路」，答案必然小得多。合用一个数的话，
 * 玩家为看清全屏界面调到 150%，HUD 就会糊住半个屏幕。
 *
 * 所以这里的范围是 40–150%，默认 60%——136×216 的机身在 60% 下是 82×130，
 * 在最窄的 640×360 逻辑画面上占约三分之一高，够看清状态栏和角标，也还留得下视野。
 *
 * 存哪儿
 *
 * 客户端配置（{@link ClientConfig}），与界面大小、字体颜色同一个道理：它描述的是
 * 「这块屏幕上怎么摆好看」，跟存档和服务器都没关系。渲染每帧都要问位置，所以值在
 * 配置加载时【推】到这里的静态字段上，一次都不去碰配置。
 */
public final class PhoneHudPlacement {

    private PhoneHudPlacement() {}

    /**
     * 九个锚点。两个序号都是 0 靠前、1 居中、2 靠后，
     * 解算见 {@link #originX}——这么编号是为了让位置算式只有一句。
     */
    public enum Anchor {
        TOP_LEFT(0, 0),
        TOP_CENTER(1, 0),
        TOP_RIGHT(2, 0),
        MIDDLE_LEFT(0, 1),
        MIDDLE_CENTER(1, 1),
        MIDDLE_RIGHT(2, 1),
        BOTTOM_LEFT(0, 2),
        BOTTOM_CENTER(1, 2),
        BOTTOM_RIGHT(2, 2);

        private final int col;
        private final int row;

        Anchor(int col, int row) {
            this.col = col;
            this.row = row;
        }

        public int col() { return col; }

        public int row() { return row; }

        /** 界面上写的名字，例如 mcphone.hud.anchor.bottom_right */
        public String translationKey() {
            return "mcphone.hud.anchor." + name().toLowerCase(java.util.Locale.ROOT);
        }

        /** 按行列取一个，越界时回落到默认锚点 */
        public static Anchor of(int col, int row) {
            for (Anchor a : values()) {
                if (a.col == col && a.row == row) return a;
            }
            return DEFAULT_ANCHOR;
        }
    }

    /** 默认贴右下角：那儿离准星最远，挡视野最少，也是绝大多数模组 HUD 的落点 */
    public static final Anchor DEFAULT_ANCHOR = Anchor.BOTTOM_RIGHT;

    /** 最小 40%。再小状态栏的时间就糊成一团，挂着也读不出来 */
    public static final int MIN_PERCENT = 40;

    /** 最大 150%。再大在 640×360 的逻辑画面上已经比屏幕还高 */
    public static final int MAX_PERCENT = 150;

    /** 加减一次走多少 */
    public static final int STEP_PERCENT = 10;

    public static final int DEFAULT_PERCENT = 60;

    /** 偏移的上下限。够任何分辨率把手机从一角挪到另一角，也堵住手改配置填个天文数字 */
    public static final int MAX_OFFSET = 4096;

    private static boolean enabled = true;
    private static Anchor anchor = DEFAULT_ANCHOR;
    private static int offsetX;
    private static int offsetY;
    private static int percent = DEFAULT_PERCENT;

    /** 上一次真的写进配置的倍数，理由与 {@link PhoneScale#commit()} 同：拖动时不写盘 */
    private static int savedPercent = DEFAULT_PERCENT;

    //  配置推进来

    static void load(boolean on, Anchor a, int ox, int oy, int scale) {
        enabled = on;
        anchor = a == null ? DEFAULT_ANCHOR : a;
        offsetX = clampOffset(ox);
        offsetY = clampOffset(oy);
        percent = clampPercent(scale);
        savedPercent = percent;
    }

    //  读

    public static boolean enabled() { return enabled; }

    public static Anchor anchor() { return anchor; }

    public static int offsetX() { return offsetX; }

    public static int offsetY() { return offsetY; }

    public static int percent() { return percent; }

    /** 渲染要的那个倍数 */
    public static float scale() { return percent / 100.0F; }

    //  写

    public static void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        ClientConfig.saveHudEnabled(value);
    }

    /**
     * 松手时落一次盘。拖的过程中走 {@link #preview}。
     *
     * 值没变就一个字节都不写：滚轮改大小之后会顺手把位置夹一次，而绝大多数时候手机
     * 根本没顶出窗口，位置压根没动。不判一下的话，滚一格就要白写一次配置文件。
     */
    public static void setPlacement(Anchor a, int ox, int oy) {
        Anchor next = a == null ? DEFAULT_ANCHOR : a;
        int nx = clampOffset(ox);
        int ny = clampOffset(oy);
        if (next == anchor && nx == offsetX && ny == offsetY) return;

        anchor = next;
        offsetX = nx;
        offsetY = ny;
        ClientConfig.saveHudPlacement(anchor, offsetX, offsetY);
    }

    /** 只改不落盘，编辑器拖动时每一步走这条 */
    public static void preview(Anchor a, int ox, int oy) {
        anchor = a == null ? DEFAULT_ANCHOR : a;
        offsetX = clampOffset(ox);
        offsetY = clampOffset(oy);
    }

    public static void setPercent(int value) {
        previewPercent(value);
        commitPercent();
    }

    public static void previewPercent(int value) {
        percent = clampPercent(value);
    }

    /** 松手时把拖出来的倍数落一次盘。没变过就什么都不做 */
    public static void commitPercent() {
        if (percent == savedPercent) return;
        savedPercent = percent;
        ClientConfig.saveHudScale(percent);
    }

    public static void nudgePercent(int delta) {
        setPercent(percent + delta);
    }

    /** 回到出厂的位置与大小。位置与倍数分两次落盘，各自的配置项才对得上 */
    public static void reset() {
        setPlacement(DEFAULT_ANCHOR, 0, 0);
        setPercent(DEFAULT_PERCENT);
    }

    public static int clampPercent(int value) {
        return Mth.clamp(value, MIN_PERCENT, MAX_PERCENT);
    }

    public static int clampOffset(int value) {
        return Mth.clamp(value, -MAX_OFFSET, MAX_OFFSET);
    }

    //  解算

    /**
     * 这一帧真正用的倍数：玩家要的，与窗口放得下的，取小。
     *
     * 与 {@link PhoneScale#effective} 同一套路数——夹在渲染时而不是夹在设置里，
     * 因为窗口随时会变，而配置里那个数是玩家的意愿，不该被一次临时的小窗口永久改小。
     */
    public static float effectiveScale(int windowWidth, int windowHeight) {
        return Math.min(scale(), PhoneScale.fit(windowWidth, windowHeight));
    }

    /** 机身（含边框）左上角的 X。窗口坐标，不是屏幕内区域 */
    public static int originX(int windowWidth, int windowHeight) {
        int phoneW = Math.round(PhoneTheme.PHONE_TOTAL_WIDTH * effectiveScale(windowWidth, windowHeight));
        return anchor.col() * (windowWidth - phoneW) / 2 + offsetX;
    }

    /** 机身（含边框）左上角的 Y */
    public static int originY(int windowWidth, int windowHeight) {
        int phoneH = Math.round(PhoneTheme.PHONE_TOTAL_HEIGHT * effectiveScale(windowWidth, windowHeight));
        return anchor.row() * (windowHeight - phoneH) / 2 + offsetY;
    }

    /** 这一帧机身占的宽（含边框），编辑器画拖动框要用 */
    public static int width(int windowWidth, int windowHeight) {
        return Math.round(PhoneTheme.PHONE_TOTAL_WIDTH * effectiveScale(windowWidth, windowHeight));
    }

    public static int height(int windowWidth, int windowHeight) {
        return Math.round(PhoneTheme.PHONE_TOTAL_HEIGHT * effectiveScale(windowWidth, windowHeight));
    }

    /**
     * 反过来：玩家把手机拖到了 (x, y)，该记成哪个锚点加多少偏移。
     *
     * 九个锚点各算一次自己的落点，取离拖放位置最近的那个——这样偏移永远是最小的那份，
     * 也就最经得起换分辨率。按「落在屏幕的哪三分之一」来判也能用，但那会在贴边时选出
     * 一个偏移很大的锚点：手机明明贴着左边，中心却还在左三分之一之外。
     */
    public static Placement derive(int x, int y, int windowWidth, int windowHeight) {
        int phoneW = width(windowWidth, windowHeight);
        int phoneH = height(windowWidth, windowHeight);

        int bestCol = 0, bestRow = 0;
        int bestDx = Integer.MAX_VALUE, bestDy = Integer.MAX_VALUE;

        for (int col = 0; col <= 2; col++) {
            int dx = x - col * (windowWidth - phoneW) / 2;
            if (Math.abs(dx) < Math.abs(bestDx)) { bestDx = dx; bestCol = col; }
        }
        for (int row = 0; row <= 2; row++) {
            int dy = y - row * (windowHeight - phoneH) / 2;
            if (Math.abs(dy) < Math.abs(bestDy)) { bestDy = dy; bestRow = row; }
        }

        return new Placement(Anchor.of(bestCol, bestRow), bestDx, bestDy);
    }

    /**
     * 拖动落地的那一步：想把机身左上角放到 (x, y)，该记成什么。
     *
     * 先夹进窗口再算锚点。允许拖出窗口的话，玩家一不小心把手机甩到画面外，回到游戏里
     * 就再也看不见它，只能去翻配置文件——而他多半根本不知道有那个文件。
     *
     * 三个调用方：HUD 上直接拖（{@link PhoneScreen}）、全屏编辑器里拖、以及手机被调大
     * 之后重新夹一次。同一套算法抄三遍迟早会出现"这儿夹了那儿没夹"。
     */
    public static Placement place(int x, int y, int windowWidth, int windowHeight) {
        int w = width(windowWidth, windowHeight);
        int h = height(windowWidth, windowHeight);
        return derive(
                Mth.clamp(x, 0, Math.max(0, windowWidth - w)),
                Mth.clamp(y, 0, Math.max(0, windowHeight - h)),
                windowWidth, windowHeight);
    }

    /** 按现在的尺寸把手机重新夹进窗口。调大之后可能顶出去，这一下把它拉回来 */
    public static Placement clampIntoWindow(int windowWidth, int windowHeight) {
        return place(originX(windowWidth, windowHeight),
                originY(windowWidth, windowHeight),
                windowWidth, windowHeight);
    }

    /** {@link #derive} 的结果 */
    public record Placement(Anchor anchor, int offsetX, int offsetY) {}
}
