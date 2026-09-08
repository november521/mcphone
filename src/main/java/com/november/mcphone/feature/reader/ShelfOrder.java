package com.november.mcphone.feature.reader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 书架排序的算术 —— 把可见列表里的第几本挪到第几位，落到磁盘上那份完整顺序里。
 *
 * <h2>为什么单独一个类，而且【不 import 任何 Minecraft 类型】</h2>
 *
 * 与 {@link BookSearch}、{@code HomeLayout} 同一个理由：这里全是下标搬运，而下标搬运的
 * 错法只有一种表现——<b>东西挪到了旁边那一格</b>。往下拖一位与往下拖两位在游戏里长得一模一样，
 * 肉眼分不出，只能靠断言钉住（见 {@code docs/ShelfOrderTest.java}）。
 *
 * <h2>为什么不能直接对可见的那张表排序</h2>
 *
 * 磁盘上记着的是玩家收藏过的<b>全部</b>书，其中一部分在当前整合包里认不出来——换过整合包、
 * 卸过模组的存档都会这样，而它们<b>刻意不删</b>（见 {@code ShelfStore} 的类注释：删了就回不来了）。
 * 界面上看得见的只是其中一个子序列。
 *
 * 于是"把第 2 本拖到第 5 本前面"要落到完整顺序上，就得回答：那些看不见的条目怎么办？
 *
 * <h2>看不见的条目钉在原地</h2>
 *
 * 做法是：可见的那些书占着完整顺序里的哪几个<b>位置</b>（下标），排完之后还是那几个位置，
 * 只是里面装的书换了。看不见的条目一个都不动。
 *
 * <pre>
 *   完整顺序   A  x  B  C  y  D        （小写＝当前认不出的书）
 *   可见的     A     B  C     D
 *   位置       0     2  3     5
 *
 *   把 D 拖到最前 →  D  x  A  B  y  C
 *                    ↑     ↑  ↑     ↑   还是 0/2/3/5 这四个位置
 * </pre>
 *
 * 换个整合包回来，那些看不见的书仍然夹在原来的邻居中间。要是改成"把可见的排好再把
 * 看不见的接在后面"，玩家每拖一次，另一个整合包里的书架顺序就被搅一次——而他根本看不见
 * 自己动了什么。
 */
public final class ShelfOrder {

    private ShelfOrder() {}

    /**
     * 把可见列表里第 {@code from} 本挪到第 {@code to} 位，返回改过的完整顺序。
     *
     * 语义是<b>拿起来再插进去</b>（不是与目标交换）：从第 1 位拖到第 3 位，原来的第 2、3 位
     * 各往前挪一格。交换的话中间那些书会莫名其妙地跳位置，而玩家只拖了一本。
     *
     * @param all     完整顺序，元素可以有看不见的（不在 visible 里的）
     * @param visible 界面上看得见的那些，必须是 {@code all} 的子序列且不重复
     * @param from    在 {@code visible} 里的下标
     * @param to      挪到 {@code visible} 里的哪个下标
     * @return 新的完整顺序。下标越界、没得可挪时原样返回 {@code all}（不抛）——
     *         这是界面调的，一次误判不该把书架页带崩
     */
    public static <T> List<T> move(List<T> all, List<T> visible, int from, int to) {
        int n = visible.size();
        if (n < 2) return all;
        if (from < 0 || from >= n) return all;

        // 夹进合法范围而不是拒绝：拖到列表外面松手，玩家的意思就是"放到最上/最下"
        int target = Math.max(0, Math.min(n - 1, to));
        if (target == from) return all;

        // 可见的那些占着完整顺序里的哪几个位置
        Set<T> visibleSet = new HashSet<>(visible);
        List<Integer> slots = new ArrayList<>(n);
        for (int i = 0; i < all.size(); i++) {
            if (visibleSet.contains(all.get(i))) slots.add(i);
        }
        // 对不上就别动：调用方给的两张表不是一回事，猜下去只会把顺序搅乱
        if (slots.size() != n) return all;

        List<T> reordered = new ArrayList<>(visible);
        reordered.add(target, reordered.remove(from));

        List<T> out = new ArrayList<>(all);
        for (int i = 0; i < n; i++) out.set(slots.get(i), reordered.get(i));
        return out;
    }

    /**
     * 鼠标停在这个位置时，松手会插到第几位。
     *
     * 按<b>行的中线</b>算：光标过了某一行的一半就该排到它后面去。按行首算的话，
     * 往下拖必须多拖一整行才有反应，手感上像是"拖不动"。
     *
     * @param y      光标的 y
     * @param top    第一行的上沿
     * @param rowH   一行多高
     * @param count  一共几行
     * @return [0, count-1] 之间的下标
     */
    public static int dropIndex(double y, int top, int rowH, int count) {
        if (count <= 0 || rowH <= 0) return 0;

        int index = (int) Math.floor((y - top + rowH / 2.0) / rowH);
        return Math.max(0, Math.min(count - 1, index));
    }
}
