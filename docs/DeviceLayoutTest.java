package com.november.mcphone.core.client;

import com.november.mcphone.core.DeviceKind;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备尺寸与图标网格排布的断言测试，用 javac 单独编，不需要 Minecraft。
 *
 * <h2>它守的是什么</h2>
 *
 * 平板这件事把"屏幕多大"从两个编译期常量变成了一个跟着设备走的值（{@link DeviceMetrics}），
 * 而列数、网格起点也跟着从定值变成了算出来的。这类改动最怕的不是崩，是<b>悄悄差一格</b>：
 * 图标偏半个间距、最后一列点不到、平板上多算出一行结果压住导航栏 —— 在游戏里全都得靠
 * 肉眼分辨，而肉眼分不出 8 和 12。
 *
 * 所以这份测试的第一件事是<b>钉死手机</b>：改成"按屏幕宽算列数、整排居中"之后，手机上
 * 算出来必须仍是 4 列、左边空 8，与从前写死的那两个数一模一样。这一条红了，就说明平板
 * 把手机的排布改掉了。
 */
public class DeviceLayoutTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(what + "  期望 " + expected + "，实际 " + actual);
        }
    }

    static void check(boolean cond, String what) {
        checks++;
        if (!cond) failures.add(what);
    }

    public static void main(String[] args) {
        metrics();
        phoneLayoutUnchanged();
        tabletLayout();
        navBar();
        photoGrid();
        cellsThatFitEdges();
        gridInsetEdges();
        slotHitTesting();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + checks + " 条断言");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 条：");
            for (String f : failures) System.out.println("  ✗ " + f);
            System.exit(1);
        }
    }

    //  一格多大（图标 + 右边那道间距），主屏与商店同一个数
    static final int CELL_W = PhoneTheme.APP_ICON_SIZE + PhoneTheme.APP_GRID_SPACING_X;

    /** 一格多高：图标 + 名字那行 + 4。名字按默认字体 lineHeight 9、缩到 0.6 算，即 5 */
    static final int CELL_H = PhoneTheme.APP_ICON_SIZE + 5 + 4;

    static void metrics() {
        eq(DeviceMetrics.PHONE.screenW(), 120, "手机屏幕宽");
        eq(DeviceMetrics.PHONE.screenH(), 200, "手机屏幕高");
        eq(DeviceMetrics.TABLET.screenW(), 240, "平板屏幕宽");
        eq(DeviceMetrics.TABLET.screenH(), 168, "平板屏幕高");

        eq(DeviceMetrics.PHONE.totalWidth(), 136, "手机含边框宽");
        eq(DeviceMetrics.PHONE.totalHeight(), 216, "手机含边框高");
        eq(DeviceMetrics.TABLET.totalWidth(), 256, "平板含边框宽");
        eq(DeviceMetrics.TABLET.totalHeight(), 184, "平板含边框高");

        // 原版保证的最小逻辑窗口是 320×240。机身比它还大的话，一进游戏就被 PhoneScale.fit
        // 缩着显示，玩家永远看不到 100% 那一档
        check(DeviceMetrics.TABLET.totalWidth() <= 320 && DeviceMetrics.TABLET.totalHeight() <= 240,
                "平板机身要塞得进 320×240 的最小逻辑窗口");

        // 内容区 —— 页面能用的那块。手机上导航条横在底下，平板上立在右边，
        // 于是一个扣的是高、一个扣的是宽
        eq(DeviceMetrics.PHONE.contentWidth(), 120, "手机内容区宽＝整块屏幕");
        eq(DeviceMetrics.PHONE.contentHeight(), 176, "手机内容区高（扣状态栏与导航栏）");
        eq(DeviceMetrics.TABLET.contentWidth(), 226, "平板内容区宽（扣掉右边那条导航条）");
        eq(DeviceMetrics.TABLET.contentHeight(), 158, "平板内容区高（只扣状态栏）");

        check(!DeviceMetrics.PHONE.sideNav(), "手机的导航条横在底下");
        check(DeviceMetrics.TABLET.sideNav(), "平板的导航条立在右边");
        eq(DeviceMetrics.PHONE.bottomNavHeight(), PhoneTheme.NAV_BAR_HEIGHT,
                "手机屏幕底下被导航条占掉 14");
        eq(DeviceMetrics.TABLET.bottomNavHeight(), 0,
                "平板屏幕底下没有导航条，页面一直排到最下面");
        eq(DeviceMetrics.PHONE.navThickness(), DeviceMetrics.TABLET.navThickness(),
                "两台设备的导航条一样厚——它只是立了起来，不该跟着变粗");

        // 内容区加导航条正好是一整块屏幕，一个像素都不重叠、不空着
        eq(DeviceMetrics.TABLET.contentWidth() + DeviceMetrics.TABLET.navThickness(),
                DeviceMetrics.TABLET.screenW(), "平板：内容区 + 导航条 ＝ 屏幕宽");
        eq(DeviceMetrics.PHONE.contentHeight() + PhoneTheme.STATUS_BAR_HEIGHT
                + DeviceMetrics.PHONE.bottomNavHeight(),
                DeviceMetrics.PHONE.screenH(), "手机：状态栏 + 内容区 + 导航条 ＝ 屏幕高");

        check(!DeviceMetrics.PHONE.landscape(), "手机是竖屏");
        check(DeviceMetrics.TABLET.landscape(), "平板是横屏");

        eq(DeviceMetrics.of(DeviceKind.PHONE), DeviceMetrics.PHONE, "PHONE 取手机那套");
        eq(DeviceMetrics.of(DeviceKind.TABLET), DeviceMetrics.TABLET, "TABLET 取平板那套");
        // 位置失效时 kindOf 给 null，那一帧仍然要有一套尺寸能画
        eq(DeviceMetrics.of(null), DeviceMetrics.PHONE, "拿不到设备时退回手机那套");
    }

    /**
     * 手机上的排布一个像素都没变。
     *
     * 这几个数从前是写死的：{@code APP_COLUMNS = 4}、{@code APP_GRID_PADDING_LEFT = 8}。
     * 现在一个由屏幕宽算、一个由居中算，算出来必须还是它们。
     */
    static void phoneLayoutUnchanged() {
        int w = DeviceMetrics.PHONE.contentWidth();

        eq(HomeLayout.cellsThatFit(w, CELL_W, PhoneTheme.APP_COLUMNS_MAX), 4,
                "手机主屏仍是一行 4 个");
        eq(HomeLayout.gridInset(w, 4, CELL_W, PhoneTheme.APP_GRID_SPACING_X), 8,
                "手机主屏左边仍空 8（与从前写死的左边距相同）");

        // 右边留白要与左边一样，否则整排看着偏
        int content = 4 * CELL_W - PhoneTheme.APP_GRID_SPACING_X;
        eq(w - content - 8, 8, "手机主屏左右留白一样宽");

        eq(rowsOn(DeviceMetrics.PHONE), 5, "手机一页 5 行");
        eq(4 * rowsOn(DeviceMetrics.PHONE), 20, "手机一页 20 个 App");
    }

    static void tabletLayout() {
        int w = DeviceMetrics.TABLET.contentWidth();
        int cols = HomeLayout.cellsThatFit(w, CELL_W, PhoneTheme.APP_COLUMNS_MAX);

        eq(cols, 8, "平板一行 8 个");
        eq(HomeLayout.gridInset(w, cols, CELL_W, PhoneTheme.APP_GRID_SPACING_X), 5,
                "平板整排居中后左边空 5");

        int content = cols * CELL_W - PhoneTheme.APP_GRID_SPACING_X;
        check(content <= w, "一排图标不能比内容区还宽");

        // 最右一列的右边缘不能伸到导航条底下 —— 排的是内容区，不是整块屏幕
        int inset = HomeLayout.gridInset(w, cols, CELL_W, PhoneTheme.APP_GRID_SPACING_X);
        check(inset + content <= DeviceMetrics.TABLET.screenW() - DeviceMetrics.TABLET.navThickness(),
                "平板最右一列不能压在右边那条导航条底下");

        eq(rowsOn(DeviceMetrics.TABLET), 4, "平板一页 4 行（矮一截，行数比手机少）");
        eq(cols * rowsOn(DeviceMetrics.TABLET), 32, "平板一页 32 个 App，是手机的 1.6 倍");

        // 图标区不能压到底下的页码点上
        int gridTop = PhoneTheme.STATUS_BAR_HEIGHT + PhoneTheme.APP_GRID_PADDING_TOP;
        check(gridTop + rowsOn(DeviceMetrics.TABLET) * CELL_H <= dotsTop(DeviceMetrics.TABLET),
                "平板的图标区不能盖住页码点那一条");
    }

    /**
     * 导航条在哪儿、点哪儿算哪个键。
     *
     * 这一段守的是"看得见点不到"：条从底下立到右边之后，画的那套坐标与判命中的那套
     * 必须还是同一套（{@link NavBarLayout} 就是为此存在的）。手机那几条一并钉住 ——
     * 它一个像素都不该动。
     *
     * 这里只问格子编号，不问是哪个键：{@code NAV_ORDER} 那张表在 PhoneChassis 里，而它
     * 一碰就要拉进整个 Minecraft。0/1/2 分别是 ◁ ○ □，-1 是没落在条上。
     */
    static void navBar() {
        final int cells = 3;
        final int left = 37, top = 91;   // 机身位置取个非零值，免得写死 0 掩盖了偏移

        // ---- 手机：横在屏幕最下面，三个键左右排 ----
        DeviceMetrics p = DeviceMetrics.PHONE;
        NavBarLayout pb = NavBarLayout.of(left, top, p);

        eq(pb.x(), left, "手机：条从屏幕最左边起");
        eq(pb.y(), top + 200 - PhoneTheme.NAV_BAR_HEIGHT, "手机：条贴着屏幕底边");
        eq(pb.w(), 120, "手机：条横贯整块屏幕");
        eq(pb.h(), PhoneTheme.NAV_BAR_HEIGHT, "手机：条厚 14");
        check(!pb.vertical(), "手机：条是横着的");
        eq(pb.span(), 120, "手机：三个键分的是屏幕宽");

        int mid = pb.y() + PhoneTheme.NAV_BAR_HEIGHT / 2;
        eq(pb.cellAt(left + 10, mid, cells), 0, "手机：左边三分之一是返回");
        eq(pb.cellAt(left + 60, mid, cells), 1, "手机：正中是主页");
        eq(pb.cellAt(left + 110, mid, cells), 2, "手机：右边三分之一是多任务");
        eq(pb.cellAt(left + 39, mid, cells), 0, "手机：一格 40，第 39 列还是返回");
        eq(pb.cellAt(left + 40, mid, cells), 1, "手机：第 40 列已经是主页");
        eq(pb.cellAt(left + 60, pb.y() - 1, cells), -1,
                "手机：条上面一个像素就不算了，那是页面的地方");
        eq(pb.cellAt(left + 60, top + 200, cells), -1, "手机：屏幕外不算");
        eq(pb.cellFrom(1, cells), 40, "手机：第二个键从 40 起（与从前写死的 screenW/3 相同）");

        // ---- 平板：立在右边，三个键上下排 ----
        DeviceMetrics t = DeviceMetrics.TABLET;
        NavBarLayout tb = NavBarLayout.of(left, top, t);

        eq(tb.x(), left + 226, "平板：条贴着屏幕右边，左边缘正是内容区的右边缘");
        eq(tb.x(), left + t.contentWidth(), "平板：条与内容区严丝合缝，不重叠也不留缝");
        eq(tb.y(), top + PhoneTheme.STATUS_BAR_HEIGHT, "平板：条从状态栏下面起，不占右上角的时钟");
        eq(tb.w(), PhoneTheme.NAV_BAR_HEIGHT, "平板：条立起来之后 14 是它的宽");
        eq(tb.h(), 158, "平板：条一直到屏幕最下面");
        check(tb.vertical(), "平板：条是立着的");
        eq(tb.span(), 158, "平板：三个键分的是屏幕高");

        int col = tb.x() + tb.w() / 2;
        eq(tb.cellAt(col, tb.y() + 5, cells), 0, "平板：最上面那格是返回");
        eq(tb.cellAt(col, tb.y() + 79, cells), 1, "平板：中间那格是主页");
        eq(tb.cellAt(col, top + 168 - 1, cells), 2,
                "平板：最下面一个像素也要落在多任务上——余数归最后一格");
        eq(tb.cellAt(col, tb.y() + 51, cells), 0, "平板：一格 52，第 51 行还是返回");
        eq(tb.cellAt(col, tb.y() + 52, cells), 1, "平板：第 52 行已经是主页");

        eq(tb.cellAt(tb.x() - 1, tb.y() + 5, cells), -1, "平板：条左边一个像素是内容区，不算");
        eq(tb.cellAt(col, tb.y() - 1, cells), -1,
                "平板：右上角那块是状态栏（时钟），不是返回键");
        eq(tb.cellAt(left + 8, top + 100, cells), -1, "平板：屏幕左边当然不算");

        // 最后一格必须一直铺到条尾：158 除以 3 是 52，末尾那 2 个像素不能没人认领
        eq(tb.cellTo(2, cells), tb.span(), "平板：最后一格吃掉除不尽的余数");
        eq(tb.cellTo(0, cells), tb.cellFrom(1, cells), "格子首尾相接，中间不留缝");
        eq(tb.cellTo(1, cells), tb.cellFrom(2, cells), "格子首尾相接，中间不留缝");
    }

    /** 图标区能用的高度：从网格起点到页码点那一条 */
    static int rowsOn(DeviceMetrics m) {
        int available = dotsTop(m) - PhoneTheme.STATUS_BAR_HEIGHT - PhoneTheme.APP_GRID_PADDING_TOP;
        return HomeLayout.cellsThatFit(available, CELL_H, PhoneTheme.APP_ROWS);
    }

    static int dotsTop(DeviceMetrics m) {
        return m.screenH() - m.bottomNavHeight() - PhoneTheme.PAGE_DOTS_HEIGHT;
    }

    /**
     * 相册/挑图那块缩略图网格。它不走 HomeLayout（格子之间只有间隙、末尾没有），
     * 但同样要"手机上一格不差、平板上多放几列"。
     */
    static void photoGrid() {
        // 内容区宽 = 页面拿到的宽 - 两侧各 6 的留白，与 Gallery 里那个 w 一致
        int phoneContent = DeviceMetrics.PHONE.contentWidth() - 12;
        int tabletContent = DeviceMetrics.TABLET.contentWidth() - 12;

        eq(com.november.mcphone.feature.gallery.client.PhotoGridPainter.colsFor(phoneContent), 3,
                "手机相册仍是一行 3 张（与从前写死的 COLS 相同）");
        eq(com.november.mcphone.feature.gallery.client.PhotoGridPainter.colsFor(tabletContent), 5,
                "平板相册一行 5 张");

        check(com.november.mcphone.feature.gallery.client.PhotoGridPainter.gridWidth(5) <= tabletContent,
                "5 张一行要放得进平板的内容区");
        eq(com.november.mcphone.feature.gallery.client.PhotoGridPainter.colsFor(0), 1,
                "宽度为 0 时也给 1 列，不能除出 0 来（后面要拿它取模）");
    }

    static void cellsThatFitEdges() {
        eq(HomeLayout.cellsThatFit(0, 28, 8), 1,
                "一格都放不下时也给 1 —— 画出格比画一片空白好懂");
        eq(HomeLayout.cellsThatFit(27, 28, 8), 1, "差一点放不下第一格，仍是 1");
        eq(HomeLayout.cellsThatFit(1000, 28, 8), 8, "再宽也不超过上限");
        eq(HomeLayout.cellsThatFit(100, 0, 8), 1, "格子宽 0 不能除，退回 1 而不是崩");
        eq(HomeLayout.cellsThatFit(100, 28, 0), 1, "上限 0 也退回 1");
        eq(HomeLayout.cellsThatFit(-50, 28, 8), 1, "负数也不会算出负的列数");
    }

    static void gridInsetEdges() {
        eq(HomeLayout.gridInset(226, 8, 28, 8), 5, "平板：(226 - (8×28 - 8)) / 2");
        eq(HomeLayout.gridInset(120, 4, 28, 8), 8, "手机：(120 - (4×28 - 8)) / 2");

        // 末尾那道间距必须减掉，否则整排会往左偏半个间距
        check(HomeLayout.gridInset(226, 8, 28, 8) > (226 - 8 * 28) / 2,
                "算内容宽时要减掉最后一格右边那道间距");

        eq(HomeLayout.gridInset(50, 8, 28, 8), 0,
                "格子比地方还多时不给负的起点，宁可出格");
    }

    /** 平板 8 列之后，落点算的是第几格 —— 差一列在游戏里看不出来，在这儿看得出来 */
    static void slotHitTesting() {
        int gridX = 12, gridY = 20, cols = 8, rows = 4;

        eq(HomeLayout.slotAt(gridX, gridY, gridX, gridY, CELL_W, CELL_H, cols, rows), 0,
                "左上角那一格是 0");
        eq(HomeLayout.slotAt(gridX + 7 * CELL_W, gridY, gridX, gridY, CELL_W, CELL_H, cols, rows), 7,
                "第一行最右是 7，不是 3 —— 平板一行有 8 个");
        eq(HomeLayout.slotAt(gridX, gridY + CELL_H, gridX, gridY, CELL_W, CELL_H, cols, rows), 8,
                "第二行第一个是 8");
        eq(HomeLayout.slotAt(gridX + 9999, gridY + 9999, gridX, gridY, CELL_W, CELL_H, cols, rows),
                cols * rows - 1, "拖到屏幕外落到最近的合法格子，即最后一格");

        eq(HomeLayout.dropIndex(1, 0, cols * rows, 100), 32,
                "第二页第一格是全局第 32 个");
        eq(HomeLayout.pageCount(33, cols * rows), 2, "33 个 App 在平板上是两页");
        eq(HomeLayout.pageCount(33, 20), 2, "同样 33 个在手机上也是两页（一页 20）");
    }
}
