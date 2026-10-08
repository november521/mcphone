package com.november.mcphone.feature.chat.client;

/** 手机、平板和中英文按钮的布局边界：缩小字体后不能挤出输入框，也不能因取整让正文越界。 */
public class ChatLayoutTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(!ChatLayout.hasText(null), "尚未创建输入框");
        check(!ChatLayout.hasText(""), "空输入隐藏发送");
        check(!ChatLayout.hasText(" \t\n\u3000"), "全空白输入隐藏发送");
        check(ChatLayout.hasText("1"), "数字显示发送");
        check(ChatLayout.hasText(" 中文 "), "中文显示发送");
        for (int width : new int[]{112, 232}) {
            for (int labelWidth : new int[]{14, 18, 30}) {
                var c = ChatLayout.composer(width, labelWidth);
                check(c.inputWidth() >= 40, "输入框需容纳正常文本");
                check(c.inputX() >= ChatLayout.ICON_SIZE + 2, "语音不能覆盖输入框");
                check(c.inputX() + c.inputWidth() < c.stickerX(), "表情不能覆盖输入框");
                check(c.stickerX() + ChatLayout.ICON_SIZE < c.actionX(), "发送不能覆盖表情");
                check(c.actionX() + c.actionWidth() <= width, "右侧动作不能越界");
                check(c.actionWidth() >= labelWidth + 4, "中英文发送文字都有内边距");
            }
        }
        for (float scale : new float[]{ChatLayout.TEXT_SCALE, ChatLayout.META_SCALE}) {
            for (int width = 6; width <= 232; width++) {
                int nativeWidth = ChatLayout.unscaledWidth(width, scale);
                check(ChatLayout.scaledWidth(nativeWidth, scale) <= width, "换行后正文不能越界");
            }
        }
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
