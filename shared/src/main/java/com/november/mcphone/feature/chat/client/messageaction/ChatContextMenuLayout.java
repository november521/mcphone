package com.november.mcphone.feature.chat.client.messageaction;

/** 菜单绘制和点击共用的纯几何。菜单整体留在手机内容区，靠右/靠下时向内翻。 */
final class ChatContextMenuLayout {
    static final int PAD = 3;
    record Bounds(int x, int y, int width, int height) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + width && my >= y && my < y + height; }
    }
    record Layout(Bounds bounds, int rowHeight, int rows) {
        int rowAt(double mx, double my) {
            if (!bounds.contains(mx, my) || my < bounds.y() + PAD || my >= bounds.y() + PAD + rows * rowHeight) return -1;
            return (int) ((my - bounds.y() - PAD) / rowHeight);
        }
    }
    static Layout place(double mx, double my, Bounds viewport, int labelWidth, int textHeight, int rows) {
        int rowHeight = textHeight + 6;
        int width = Math.min(Math.max(40, labelWidth + PAD * 2 + 4), viewport.width());
        int height = rows * rowHeight + PAD * 2;
        int x = Math.max(viewport.x(), Math.min((int) Math.floor(mx), viewport.x() + viewport.width() - width));
        int y = Math.max(viewport.y(), Math.min((int) Math.floor(my), viewport.y() + viewport.height() - height));
        return new Layout(new Bounds(x, y, width, height), rowHeight, rows);
    }
}
