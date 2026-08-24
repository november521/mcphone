package com.november.mcphone.core.client;

import java.util.List;

/**
 * 主屏的排布算术 —— 一页几行、一共几页、鼠标落在第几格、拖完之后顺序变成什么样。
 *
 * 为什么单独一个类，而且【不 import 任何 Minecraft 类型】
 *
 * 这里全是纯算术：给一组数字，算出另一组数字。分页最容易出错的恰恰是这一层——
 * 差一格、越界、最后一页只有半行时落点算到了不存在的位置。而这类错误在游戏里
 * 靠肉眼极难分辨：你只看见图标"跳到了奇怪的地方"，看不出是差了 1 还是差了一整行。
 *
 * 单独摆出来、不碰 Minecraft，就能用 javac 直接编出来跑断言，不必开游戏。这是
 * 它存在的唯一理由，所以别往里加需要渲染上下文的东西——加一个 GuiGraphics 参数
 * 就等于把这份可验证性丢掉了。
 *
 * 一条【连续】的列表，切成一页页
 *
 * 主屏没有"空格子"这回事：App 是一条紧挨着的列表，第 21 个就是第二页第一格。
 * 真手机允许中间留洞，那需要另存一份"哪些格是空的"，而它跟"卸载一个 App 之后
 * 后面的要不要顶上来"永远打架——两种答案都有人觉得不对。连续列表没有这个问题，
 * 代价只是不能刻意在中间留空。
 */
public final class HomeLayout {

    private HomeLayout() {}

    /**
     * 一页放得下几行。
     *
     * @param available 图标区能用的高度（已扣掉状态栏、导航栏、页码点那一条）
     * @param cellH     一格的高度（图标 + 底下那行名字）
     * @param maxRows   行数上限
     * @return 至少 1 行。一行都放不下时也返回 1——那种极端情况下画出格
     *         也比画一片空白好懂，后者看着像"手机坏了"
     */
    public static int rowsThatFit(int available, int cellH, int maxRows) {
        if (cellH <= 0 || maxRows <= 0) return 1;
        return Math.max(1, Math.min(maxRows, available / cellH));
    }

    /**
     * 一共几页。
     *
     * 一个 App 都没有时是 1 页，不是 0 页：空主屏仍然是一屏，玩家还得站在上面。
     */
    public static int pageCount(int appCount, int pageSize) {
        if (pageSize <= 0) return 1;
        return Math.max(1, (Math.max(0, appCount) + pageSize - 1) / pageSize);
    }

    /** 把页码夹进 [0, 页数-1] */
    public static int clampPage(int page, int appCount, int pageSize) {
        return Math.max(0, Math.min(page, pageCount(appCount, pageSize) - 1));
    }


    /**
     * 鼠标落在【这一页的】第几格，0 起、行优先。
     *
     * 越界一律夹到最近的合法格子，而不是返回"没有"：拖到图标区外面松手时，最符合
     * 直觉的结果是落在最近的那一格，而不是弹回原位当无事发生。
     *
     * @param lx 屏幕坐标，已撤掉开场动画的缩放（见 PhoneScreen.unscaledX）。
     *           与 gridX/gridY 同一套坐标，直接相减即可
     */
    public static int slotAt(double lx, double ly, int gridX, int gridY,
                             int cellW, int cellH, int cols, int rows) {
        if (cellW <= 0 || cellH <= 0 || cols <= 0 || rows <= 0) return 0;

        int col = (int) Math.floor((lx - gridX) / (double) cellW);
        int row = (int) Math.floor((ly - gridY) / (double) cellH);
        col = Math.max(0, Math.min(col, cols - 1));
        row = Math.max(0, Math.min(row, rows - 1));

        return row * cols + col;
    }

    /**
     * 松手会落到第几个 App（全局下标，跨页连续算）。
     *
     * 在最后一页的空格上松手＝放到队尾，而不是"放在第 37 位、前面空着几格"——
     * 理由见类注释里那条"连续列表"。
     */
    public static int dropIndex(int page, int slot, int pageSize, int appCount) {
        if (appCount <= 0) return 0;
        return Math.max(0, Math.min(page * pageSize + slot, appCount - 1));
    }

    /**
     * 把第 from 个挪到第 to 个位置。就地改动传进来的列表。
     *
     * 【插入】而不是交换：把第 1 个拖到第 3 格，中间那些依次前移，就像真手机那样。
     * 交换的话玩家拖一个图标会让另一个莫名其妙地跳到他手指原来的位置上。
     *
     * 拖动时的实时预览与松手后的落定共用这一个方法，两者才不可能对不上——各写
     * 一遍的话，"看着会插到这儿、松手却去了那儿"这种 bug 迟早出现，而且极难查。
     *
     * @return 真的改变了顺序才返回 true；下标越界或原地不动返回 false
     */
    public static <T> boolean reorder(List<T> list, int from, int to) {
        if (list == null) return false;
        if (from < 0 || from >= list.size()) return false;

        int target = Math.max(0, Math.min(to, list.size() - 1));
        if (from == target) return false;

        list.add(target, list.remove(from));
        return true;
    }
}
