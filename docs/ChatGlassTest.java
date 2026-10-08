package com.november.mcphone.feature.chat.client;

import java.util.ArrayList;
import java.util.List;

/** 透明面板的可见性、渐变及圆角边界；不用启动游戏即可检查绘制数据。 */
public class ChatGlassTest {
    private static int checks;
    private record Rect(int x1, int y1, int x2, int y2, int color) {}

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        for (var surface : ChatGlass.Surface.values()) {
            for (boolean darkText : new boolean[]{false, true}) {
                var normal = ChatGlass.palette(surface, darkText, false);
                var hover = ChatGlass.palette(surface, darkText, true);
                for (var p : List.of(normal, hover)) {
                    for (int color : new int[]{p.top(), p.bottom(), p.edge(), p.shine()}) {
                        check((color >>> 24) > 0 && (color >>> 24) < 255,
                                "面板及描边不能变成不透明实心块：" + surface);
                    }
                    check(p.top() != p.bottom(), "面板需要渐变层次");
                    check(ChatGlass.interpolate(p.top(), p.bottom(), 0, 9) == p.top(), "渐变起点");
                    check(ChatGlass.interpolate(p.top(), p.bottom(), 9, 9) == p.bottom(), "渐变终点");
                }
                check((hover.top() >>> 24) > (normal.top() >>> 24), "悬停必须有可辨识反馈");
            }
        }
        for (int width : new int[]{1, 2, 9, 12, 104, 224}) {
            for (int height : new int[]{1, 2, 8, 10, 18, 24}) {
                List<Rect> rects = new ArrayList<>();
                ChatGlass.paint((a, b, c, d, color) -> rects.add(new Rect(a, b, c, d, color)),
                        11, 17, width, height, 3, ChatGlass.palette(ChatGlass.Surface.CARD, false, false));
                check(!rects.isEmpty(), "有效尺寸必须绘制");
                int scale = ChatGlass.PRECISION;
                for (var r : rects) {
                    check(r.x1() >= 11 * scale && r.x2() <= (11 + width) * scale, "圆角不能越出水平边界");
                    check(r.y1() >= 17 * scale && r.y2() <= (17 + height + 1) * scale, "阴影最多向下伸一像素");
                    check(r.x1() < r.x2() && r.y1() < r.y2(), "不能生成反向或空矩形");
                    check((r.color() >>> 24) < 255, "实际绘制颜色必须保留透明度");
                }
                int radius = Math.min(3, (Math.min(width, height) - 1) / 2);
                for (int row = 0; row < height; row++) {
                    check(ChatGlass.inset(row, height, radius)
                            == ChatGlass.inset(height - 1 - row, height, radius), "上下圆角必须对称");
                }
            }
        }
        ChatGlass.Fill fail = (a, b, c, d, color) -> { throw new AssertionError("无效尺寸不应绘制"); };
        var p = ChatGlass.palette(ChatGlass.Surface.CARD, false, false);
        ChatGlass.paint(fail, 0, 0, 0, 10, 3, p);
        ChatGlass.paint(fail, 0, 0, 10, -1, 3, p);
        List<Rect> solid = new ArrayList<>();
        ChatGlass.paintSolid((a, b, c, d, color) -> solid.add(new Rect(a, b, c, d, color)),
                0, 0, 40, 24, 3, 0xFFFFFFFF);
        check(solid.stream().anyMatch(r -> r.x1() % ChatGlass.PRECISION != 0), "圆弧需要亚像素精度");
        check(solid.size() < 24 * ChatGlass.PRECISION, "直线区域不能无意义地细分八次");
        boolean[][] covered = new boolean[24 * ChatGlass.PRECISION][40 * ChatGlass.PRECISION];
        for (var r : solid) {
            for (int y = r.y1(); y < r.y2(); y++) {
                for (int x = r.x1(); x < r.x2(); x++) {
                    check(!covered[y][x], "透明底色的色带不能重叠");
                    covered[y][x] = true;
                }
            }
        }
        for (int y = 0; y < covered.length; y++) {
            for (int x = 0; x < covered[y].length; x++) {
                check(covered[y][x] == covered[y][covered[y].length - 1 - x], "圆角覆盖左右对称");
                check(covered[y][x] == covered[covered.length - 1 - y][x], "圆角覆盖上下对称");
            }
        }
        List<Rect> square = new ArrayList<>();
        ChatGlass.paintSolid((a, b, c, d, color) -> square.add(new Rect(a, b, c, d, color)),
                0, 0, 14, 14, 0, 0xFFFFFFFF);
        check(square.get(0).x1() == 0 && square.get(0).x2() == 14 * ChatGlass.PRECISION,
                "头像方框顶边完整，不再裁出圆角");
        check(square.get(square.size() - 1).x1() == 0, "头像方框底角也不裁圆");
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
