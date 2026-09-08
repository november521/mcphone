package com.november.mcphone.core.client;

/**
 * 导航条摆在哪儿、三个键各占哪一段 —— 纯算术，<b>不 import 任何 Minecraft 类型</b>。
 *
 * <h2>为什么单独一个类</h2>
 *
 * 与 {@link HomeLayout} 同一个理由，只是这里的"差一格"更疼：条从屏幕底下立到了右边
 * （平板），画的那套坐标与判命中的那套只要有一处没跟着改，玩家看到的就是<b>看得见点不到</b>
 * —— 三个键还在那儿，按下去没反应。这种毛病在游戏里只能一个一个点过去试。
 *
 * 摆出来不碰 Minecraft，就能用 javac 直接编出来跑断言（见 {@code docs/DeviceLayoutTest}）。
 * {@link PhoneChassis} 画的时候取这里的格子，命中判定也取这里的格子，两边不可能对不上。
 *
 * <h2>横屏为什么把条挪到右边</h2>
 *
 * 三个键横在底下时占的是<b>最缺的那一维</b>：平板高只有 168，底下再切 14，页面能用的高度
 * 比手机还矮；而宽有 240，右边切掉 14 几乎看不出来。真平板也是这么摆的。
 *
 * @param x        条的左边缘（屏幕内坐标，已含 phoneLeft）
 * @param y        条的上边缘
 * @param vertical 立着的（键上下排）还是横着的（键左右排）
 */
public record NavBarLayout(int x, int y, int w, int h, boolean vertical) {

    /**
     * 这台设备的导航条在哪儿。
     *
     * 横屏时从<b>状态栏下面</b>起：状态栏是整条横在最上面的，时钟就在右上角那个位置，
     * 导航条再从最顶上开始的话两条会在那个角上撞起来。
     */
    public static NavBarLayout of(int phoneLeft, int phoneTop, DeviceMetrics metrics) {
        int t = metrics.navThickness();
        if (metrics.sideNav()) {
            int top = phoneTop + PhoneTheme.STATUS_BAR_HEIGHT;
            return new NavBarLayout(phoneLeft + metrics.screenW() - t, top,
                    t, phoneTop + metrics.screenH() - top, true);
        }
        return new NavBarLayout(phoneLeft, phoneTop + metrics.screenH() - t,
                metrics.screenW(), t, false);
    }

    /** 长边有多长 —— 几个键分的就是它。立着的条分高，横着的条分宽 */
    public int span() {
        return vertical ? h : w;
    }

    /** 第 i 个键在长边上从哪儿起（相对条的起点） */
    public int cellFrom(int i, int cells) {
        return span() / Math.max(1, cells) * i;
    }

    /**
     * 到哪儿止。
     *
     * 最后一个吃掉除不尽的那点余数：158 分三份是 52，末尾还剩 2 个像素——不归给谁的话，
     * 那两行画着条的底色、点下去却什么都不是。
     */
    public int cellTo(int i, int cells) {
        return i >= cells - 1 ? span() : cellFrom(i + 1, cells);
    }

    /**
     * 这个点落在第几个键上，没落在条上给 -1。
     *
     * 与 {@link #cellFrom}/{@link #cellTo} 是同一套除法，所以"画在哪儿"与"点得到哪儿"
     * 天然一致；末尾的余数同样归最后一个键。
     */
    public int cellAt(double mouseX, double mouseY, int cells) {
        if (mouseX < x || mouseX >= x + w) return -1;
        if (mouseY < y || mouseY >= y + h) return -1;

        double along = vertical ? mouseY - y : mouseX - x;
        int cell = Math.max(1, span() / Math.max(1, cells));
        return Math.min(Math.max(1, cells) - 1, (int) (along / cell));
    }
}
