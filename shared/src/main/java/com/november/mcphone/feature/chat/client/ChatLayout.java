package com.november.mcphone.feature.chat.client;

/** 聊天页的逻辑像素布局。字号换算与输入栏分区共用一份，绘制和点击不能各算各的。 */
public final class ChatLayout {
    private ChatLayout() {}

    public static final float TEXT_SCALE = 0.75f;
    public static final float META_SCALE = 0.60f;
    public static final int ICON_SIZE = 9;
    public static final int INPUT_HEIGHT = 18;
    public static final int TAB_HEIGHT = 24;
    private static final int GAP = 3;

    /** 向下取整，折行后的文字才不会越过气泡或输入框的右边界。 */
    public static int unscaledWidth(int width, float scale) {
        return Math.max(1, (int) Math.floor(width / scale));
    }

    public static int scaledWidth(int width, float scale) {
        // 0.6f 的表示误差会把整像素的 42 算成 42.000002；不能因此多占一列。
        return (int) Math.ceil(width * (double) scale - 0.0001);
    }

    public static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    /** 发送与加号共用右侧槽位，切换时输入框宽度不跳动，也不改变光标位置。 */
    public static Composer composer(int width, int sendLabelWidth) {
        int actionWidth = Math.max(18, sendLabelWidth + 6);
        int actionX = width - actionWidth;
        int stickerX = actionX - GAP - ICON_SIZE;
        int inputX = ICON_SIZE + GAP;
        int inputWidth = stickerX - GAP - inputX;
        return new Composer(inputX, inputWidth, stickerX, actionX, actionWidth);
    }

    public record Composer(int inputX, int inputWidth, int stickerX, int actionX, int actionWidth) {}
}
