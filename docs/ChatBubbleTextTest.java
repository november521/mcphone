package com.november.mcphone.feature.chat.client;

/** 默认字体短文本、多行和缩放取整的光学边距回归，不把自动化几何等同于美术验收。 */
public final class ChatBubbleTextTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static boolean near(float a, float b) { return Math.abs(a - b) < .001f; }
    public static void main(String[] args) {
        for (int advance : new int[]{6, 12, 18, 21, 51, 93}) {
            for (int lines = 1; lines <= 8; lines++) {
                int w = ChatLayout.scaledWidth(advance, ChatLayout.TEXT_SCALE) + 6;
                int h = lines * ChatLayout.scaledWidth(9, ChatLayout.TEXT_SCALE) + 4;
                var origin = ChatLayout.bubbleText(w, h, advance, 9, lines);
                float inkW = (advance - 1) * ChatLayout.TEXT_SCALE;
                float inkH = (lines - 1) * 7 + 8 * ChatLayout.TEXT_SCALE;
                check(near(origin.x(), w - origin.x() - inkW), "正文可见块左右边距相等");
                float visibleTop = origin.y() + ChatLayout.TEXT_SCALE / 2f;
                check(near(visibleTop, h - visibleTop - inkH), "补偿字形偏移后上下边距相等");
                check(origin.x() > 3, "去掉行尾间距后相较固定三像素左边距向右校准");
                float previousY = (h - ((lines - 1) * 7 + 7 * ChatLayout.TEXT_SCALE)) / 2f;
                check(near(previousY - origin.y(), .75f), "相较旧估算向上校准四分之三逻辑像素");
                check(visibleTop > 0 && visibleTop + inkH < h, "正文不碰上下气泡边缘");
            }
        }
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
