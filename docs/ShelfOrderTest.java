package com.november.mcphone.feature.reader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 书架排序的断言测试，用 javac 单独编，不需要 Minecraft。
 *
 * <h2>它守的是什么</h2>
 *
 * 拖动排序错起来只有一种样子：<b>东西落到了旁边那一格</b>。往下拖一位与往下拖两位在
 * 游戏里长得一模一样，肉眼分不出——尤其是"拿起来插进去"与"两两交换"这两种语义，
 * 只有拖过三本以上的距离才看得出区别，而那时玩家多半以为是自己手抖了。
 *
 * 另一半守的是<b>看不见的那些书</b>：换过整合包的存档里，架上记着一批当前认不出的书，
 * 它们不显示、不参与排序，但必须留在原处（理由见 {@link ShelfOrder} 的类注释）。这条
 * 在游戏里根本观察不到——要换回那个整合包才看得见结果。
 */
public class ShelfOrderTest {

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
        pickUpAndInsert();
        hiddenBooksStayPut();
        edges();
        dropIndexRounding();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + checks + " 条断言");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 条：");
            for (String f : failures) System.out.println("  ✗ " + f);
            System.exit(1);
        }
    }

    static List<String> of(String s) {
        return Arrays.asList(s.split(""));
    }

    static String str(List<String> list) {
        return String.join("", list);
    }

    /** 全都看得见的情形 —— 语义是"拿起来再插进去"，不是"与目标交换" */
    static void pickUpAndInsert() {
        List<String> all = of("ABCDE");

        eq(str(ShelfOrder.move(all, all, 0, 2)), "BCADE",
                "把第 1 本插到第 3 位：B、C 各往前挪一格，不是与 C 对调");
        eq(str(ShelfOrder.move(all, all, 4, 0)), "EABCD", "把最后一本拖到最前");
        eq(str(ShelfOrder.move(all, all, 0, 4)), "BCDEA", "把第一本拖到最后");
        eq(str(ShelfOrder.move(all, all, 2, 1)), "ACBDE", "往上挪一位＝与上一本换位置（只隔一格时两种语义相同）");
        eq(str(ShelfOrder.move(all, all, 1, 2)), "ACBDE", "往下挪一位");

        eq(str(ShelfOrder.move(all, all, 2, 2)), "ABCDE", "原地不动");

        // 交换语义下这一条会是 EBCDA，与上面 pickUp 的 EABCD 不同——这就是那对
        // 肉眼分不出的差别，钉在这里
        check(!str(ShelfOrder.move(all, all, 4, 0)).equals("EBCDA"),
                "不能实现成两两交换");
    }

    /**
     * 架上有当前认不出的书（小写）时，可见的那些只在自己占的那几个位置里重排，
     * 看不见的一个都不动。
     */
    static void hiddenBooksStayPut() {
        List<String> all = of("AxBCyD");
        List<String> visible = of("ABCD");

        eq(str(ShelfOrder.move(all, visible, 3, 0)), "DxAByC",
                "把 D 拖到最前：x 仍在第 2 位、y 仍在第 5 位");
        eq(str(ShelfOrder.move(all, visible, 0, 3)), "BxCDyA",
                "把 A 拖到最后，看不见的两本原地不动");

        // 看不见的书排在最前面时也一样——它不该被"挤"到第二位去
        List<String> leading = of("zABC");
        eq(str(ShelfOrder.move(leading, of("ABC"), 2, 0)), "zCAB",
                "开头那本看不见的仍在开头");

        for (List<String> before : List.of(of("AxBCyD"), of("zABC"), of("ABxC"))) {
            List<String> vis = new ArrayList<>();
            for (String s : before) if (!s.equals(s.toLowerCase())) vis.add(s);
            List<String> after = ShelfOrder.move(before, vis, 0, vis.size() - 1);
            check(after.size() == before.size(), "长度不变：" + str(before));
            check(new java.util.HashSet<>(after).equals(new java.util.HashSet<>(before)),
                    "一本都不能丢、也不能多出来：" + str(before));
            for (int i = 0; i < before.size(); i++) {
                String s = before.get(i);
                if (s.equals(s.toLowerCase())) {
                    eq(after.get(i), s, "看不见的 " + s + " 必须还在第 " + i + " 位");
                }
            }
        }
    }

    /** 边界：越界、太短、两张表对不上时一律原样返回，绝不抛也绝不搅乱顺序 */
    static void edges() {
        List<String> all = of("ABC");

        eq(str(ShelfOrder.move(all, all, -1, 1)), "ABC", "from 越界：原样返回");
        eq(str(ShelfOrder.move(all, all, 5, 1)), "ABC", "from 越界（大）：原样返回");
        eq(str(ShelfOrder.move(all, all, 0, 99)), "BCA", "to 越界＝夹到最后一位（拖出列表外松手）");
        eq(str(ShelfOrder.move(all, all, 2, -99)), "CAB", "to 越界（小）＝夹到第一位");

        eq(str(ShelfOrder.move(of("A"), of("A"), 0, 0)), "A", "只有一本：没得可挪");
        eq(str(ShelfOrder.move(List.of(), List.of(), 0, 0)), "", "空书架不炸");

        // 可见列表不是完整顺序的子集（界面与磁盘对不上，理论上不该发生）
        eq(str(ShelfOrder.move(of("ABC"), of("AZ"), 0, 1)), "ABC",
                "两张表对不上：什么都不动，别猜");
    }

    /** 落点按行的中线算：过了半行才算到下一位 */
    static void dropIndexRounding() {
        final int top = 40, rowH = 20, count = 5;

        eq(ShelfOrder.dropIndex(40, top, rowH, count), 0, "正对第一行的行首");
        eq(ShelfOrder.dropIndex(49, top, rowH, count), 0, "第一行的上半还是第 0 位");
        eq(ShelfOrder.dropIndex(50, top, rowH, count), 1, "过了第一行的中线就是第 1 位");
        eq(ShelfOrder.dropIndex(69, top, rowH, count), 1, "第二行的上半");
        eq(ShelfOrder.dropIndex(70, top, rowH, count), 2, "第二行的中线");

        eq(ShelfOrder.dropIndex(-500, top, rowH, count), 0, "拖到列表上面：夹到第一位");
        eq(ShelfOrder.dropIndex(5000, top, rowH, count), 4, "拖到列表下面：夹到最后一位");

        eq(ShelfOrder.dropIndex(100, top, 0, count), 0, "行高 0 不能除，退回 0 而不是崩");
        eq(ShelfOrder.dropIndex(100, top, rowH, 0), 0, "一本都没有时也给 0");
    }
}
