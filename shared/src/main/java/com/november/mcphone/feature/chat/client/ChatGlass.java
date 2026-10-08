package com.november.mcphone.feature.chat.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天首页的透明面板。只生成矩形色带，不采样屏幕、不增加模糊渲染通道。
 * 上下渐变、像素圆角、细边和顶沿高光共用一套；底色的各条色带不重复叠加。
 */
final class ChatGlass {
    private ChatGlass() {}

    /** 只细分局部轮廓，不创建八倍尺寸的屏幕或渲染缓冲。 */
    static final int PRECISION = 8;
    private static final int EDGE = PRECISION / 2;
    private record Shape(int width, int height, int radius) {}
    private record Band(int top, int bottom, int left, int right, int innerLeft, int innerRight) {}
    /** 缓存的是小型几何数据，颜色不入键；悬停、切换字体预设不会反复创建轮廓。 */
    private static final Map<Shape, List<Band>> SHAPES = new LinkedHashMap<>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Shape, List<Band>> entry) {
            return size() > 64;
        }
    };

    enum Surface { CARD, SEARCH, NAVIGATION, AVATAR, BUTTON, BADGE }

    record Palette(int top, int bottom, int edge, int shine) {}

    @FunctionalInterface
    interface Fill {
        void rectangle(int x1, int y1, int x2, int y2, int color);
    }

    static Palette palette(Surface surface, boolean darkText, boolean hovered) {
        Palette p;
        if (darkText) {
            p = switch (surface) {
                case BADGE -> new Palette(0xD4B35084, 0xCE8B3D65, 0x80C56B98, 0x88FFE0F0);
                case BUTTON -> new Palette(0x85F0C0D8, 0x60BE86A3, 0x809D527A, 0x88FFF0F8);
                default -> new Palette(0xA6FFF5FA, 0x75DFB9CF, 0x55AD809A, 0xA6FFFFFF);
            };
        } else {
            p = switch (surface) {
                case CARD -> new Palette(0x726D486D, 0x483A253E, 0x338F6C92, 0x40D9A4C8);
                case SEARCH -> new Palette(0x635F4860, 0x5A382C40, 0x588D6D86, 0x52D8AFCA);
                case NAVIGATION -> new Palette(0x566B4668, 0x723E2944, 0x40987193, 0x52DFC0D5);
                case AVATAR -> new Palette(0x38966C95, 0x464E304D, 0x36956992, 0x48D4A2C8);
                case BUTTON -> new Palette(0x426D3D60, 0x28432A43, 0x709D6B90, 0x67ECC3DD);
                case BADGE -> new Palette(0xB4BC6691, 0xA0813D67, 0x70D78BB1, 0x88FFE0F0);
            };
        }
        return hovered ? new Palette(brighter(p.top(), 20), brighter(p.bottom(), 14),
                darkText ? 0x66A95682 : 0x78F1AFD3, darkText ? 0xA6FFFFFF : 0x76FFE8F4) : p;
    }

    private static int brighter(int color, int extraAlpha) {
        return Math.min(220, (color >>> 24) + extraAlpha) << 24 | (color & 0xFFFFFF);
    }

    static int interpolate(int from, int to, int position, int end) {
        if (end <= 0) return from;
        int color = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 255;
            int b = (to >>> shift) & 255;
            color |= (a + (b - a) * position / end) << shift;
        }
        return color;
    }

    static int inset(int row, int height, int radius) {
        int edge = Math.min(row, height - 1 - row);
        if (radius <= 0 || edge >= radius) return 0;
        double dy = radius - edge - .5;
        return (int) Math.round(radius - Math.sqrt(radius * (double) radius - dy * dy));
    }

    private static synchronized List<Band> bands(int width, int height, int radius) {
        radius = Math.max(0, Math.min(radius, (Math.min(width, height) - 1) / 2));
        Shape key = new Shape(width, height, radius);
        List<Band> cached = SHAPES.get(key);
        if (cached != null) return cached;
        int w = width * PRECISION, h = height * PRECISION, r = radius * PRECISION;
        List<Band> result = new ArrayList<>();
        for (int row = 0; row < h;) {
            // 弧线与上下描边细分成 1/8 像素；直线区域仍按一个逻辑像素画，避免整面细分。
            int step = row < Math.max(r, EDGE) || row >= h - Math.max(r, EDGE) ? 1 : PRECISION;
            step = Math.min(step, h - Math.max(r, EDGE) > row ? h - Math.max(r, EDGE) - row : h - row);
            step = Math.max(1, step);
            int left = inset(row, h, r), right = w - left;
            int innerLeft = right, innerRight = left;
            if (row >= EDGE && row + step <= h - EDGE && w > EDGE * 2) {
                innerLeft = EDGE + inset(row - EDGE, h - EDGE * 2, Math.max(0, r - EDGE));
                innerRight = w - innerLeft;
            }
            result.add(new Band(row, row + step, left, right, innerLeft, innerRight));
            row += step;
        }
        cached = List.copyOf(result);
        SHAPES.put(key, cached);
        return cached;
    }

    /** 输入仍为逻辑坐标，输出为八倍坐标；调用方绘制时只需等比缩回。 */
    static void paintSolid(Fill fill, int x, int y, int width, int height, int radius, int color) {
        if (width <= 0 || height <= 0) return;
        int ox = x * PRECISION, oy = y * PRECISION;
        for (Band b : bands(width, height, radius)) {
            fill.rectangle(ox + b.left(), oy + b.top(), ox + b.right(), oy + b.bottom(), color);
        }
    }

    static void paint(Fill fill, int x, int y, int width, int height, int radius, Palette p) {
        if (width <= 0 || height <= 0) return;
        // 阴影只向下错一逻辑像素，圆弧边缘与面板一致。
        paintSolid(fill, x, y + 1, width, height, radius, 0x12061122);
        int ox = x * PRECISION, oy = y * PRECISION, h = height * PRECISION;
        for (Band b : bands(width, height, radius)) {
            int top = oy + b.top(), bottom = oy + b.bottom();
            int left = ox + b.left(), right = ox + b.right();
            fill.rectangle(left, top, right, bottom, interpolate(p.top(), p.bottom(), b.top(), h - 1));
            int edge = b.top() < h / 2 ? interpolate(p.shine(), p.edge(), b.top(), h / 2) : p.edge();
            if (b.innerLeft() >= b.innerRight()) {
                fill.rectangle(left, top, right, bottom, edge);
            } else {
                if (b.innerLeft() > b.left()) fill.rectangle(left, top, ox + b.innerLeft(), bottom, edge);
                if (b.innerRight() < b.right()) fill.rectangle(ox + b.innerRight(), top, right, bottom, edge);
            }
        }
    }
}
