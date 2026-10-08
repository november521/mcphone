package com.november.mcphone.feature.chat.client;

/** 聊天页的逻辑像素布局。字号换算与输入栏分区共用一份，绘制和点击不能各算各的。 */
public final class ChatLayout {
    private ChatLayout() {}

    public static final float TEXT_SCALE = 0.75f;
    public static final float META_SCALE = 0.60f;
    public static final float TAB_LABEL_SCALE = 0.45f;
    public static final float SEND_SCALE = 0.60f;
    public static final int ICON_SIZE = 9;
    public static final int AVATAR_SIZE = 12;
    public static final int HEADER_HEIGHT = 12;
    public static final int SEARCH_HEIGHT = 10;
    public static final int SEARCH_ICON_SIZE = 6;
    public static final int SEARCH_PAD_X = 3;
    public static final int SEARCH_TEXT_X = SEARCH_PAD_X + SEARCH_ICON_SIZE + 3;
    public static final int TOP_GAP = 2;
    public static final int SECTION_GAP = 2;
    public static final int INPUT_HEIGHT = 12;
    public static final int SEND_HEIGHT = 10;
    public static final int TAB_HEIGHT = 18;
    public static final int TAB_ICON_TOP = 2;
    public static final int TAB_LABEL_TOP = 12;
    public static final int CARD_PAD = 2;
    public static final int CARD_GAP = 2;
    public static final int AVATAR_FRAME = 1;
    public static final int AVATAR_STATUS_SIZE = 2;
    public static final int AVATAR_TEXT_GAP = 3;
    public static final int CARD_ACTION_GAP = 3;
    public static final int UNREAD_HEIGHT = 8;
    public static final int TELEPORT_ICON_SIZE = 7;
    public static final int TELEPORT_HIT_PAD = 3;
    private static final int GAP = 2;

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

    /** 空白时加号只占图标宽，有文字时才留发送按钮宽；表情始终与右侧动作相隔两个像素。 */
    public static Composer composer(int width, int sendLabelWidth, boolean hasText) {
        int actionWidth = hasText ? Math.max(14, sendLabelWidth + 4) : ICON_SIZE;
        int actionX = width - actionWidth;
        int stickerX = actionX - GAP - ICON_SIZE;
        int inputX = ICON_SIZE + GAP;
        int inputWidth = stickerX - GAP - inputX;
        return new Composer(inputX, inputWidth, stickerX, actionX, actionWidth);
    }

    public record Composer(int inputX, int inputWidth, int stickerX, int actionX, int actionWidth) {}

    /** 名称一行、摘要一行；底部同时容纳未读数与传送图标。 */
    public static int cardHeight(int nameLineHeight, int previewLineHeight) {
        return Math.max(AVATAR_SIZE + AVATAR_FRAME * 2,
                nameLineHeight + 1 + Math.max(previewLineHeight, UNREAD_HEIGHT)) + CARD_PAD * 2;
    }

    public record Rect(int x, int y, int width, int height) {
        public boolean contains(double mx, double my) { return hit(mx, my, x, y, width, height); }
    }

    public record Card(int height, Rect avatarFrame, int textX, int nameY, int nameWidth,
                       int timeX, float timeY, float previewY, int previewWidth,
                       float teleportX, float teleportY, Rect teleportHit, Rect unread) {}

    /** 图标、文字和点击范围共用底行中心；传送的纵向点击区不能跨到名称或时间上。 */
    public static Card card(int width, int nameHeight, int previewHeight, int timeWidth,
                            int unreadWidth, boolean canTeleport) {
        int h = cardHeight(nameHeight, previewHeight);
        int frameSize = AVATAR_SIZE + AVATAR_FRAME * 2;
        Rect frame = new Rect(CARD_PAD, (h - frameSize) / 2, frameSize, frameSize);
        int textX = frame.x() + frame.width() + AVATAR_TEXT_GAP;
        int footerH = Math.max(previewHeight, UNREAD_HEIGHT);
        int nameY = (h - nameHeight - 1 - footerH) / 2;
        int footerY = nameY + nameHeight + 1;
        int timeX = width - CARD_PAD - timeWidth;
        Rect unread = new Rect(width - CARD_PAD - unreadWidth,
                footerY + (footerH - UNREAD_HEIGHT) / 2, unreadWidth, UNREAD_HEIGHT);
        int previewRight = unreadWidth > 0 ? unread.x() - CARD_ACTION_GAP : width - CARD_PAD;
        Rect tpHit = new Rect(0, 0, 0, 0);
        float tpX = 0, tpY = 0;
        if (canTeleport) {
            int hitW = TELEPORT_ICON_SIZE + TELEPORT_HIT_PAD * 2;
            tpHit = new Rect(previewRight - hitW, footerY, hitW, footerH);
            tpX = tpHit.x() + TELEPORT_HIT_PAD;
            tpY = footerY + (footerH - TELEPORT_ICON_SIZE) / 2f;
            previewRight = tpHit.x() - CARD_ACTION_GAP;
        }
        int nameRight = timeWidth > 0 ? timeX - CARD_ACTION_GAP : width - CARD_PAD;
        return new Card(h, frame, textX, nameY, Math.max(0, nameRight - textX),
                timeX, nameY + (nameHeight - previewHeight) / 2f,
                footerY + (footerH - previewHeight) / 2f, Math.max(0, previewRight - textX),
                tpX, tpY, tpHit, unread);
    }

    public static int visibleCards(int availableHeight, int cardHeight) {
        return Math.max(1, (availableHeight + CARD_GAP) / (cardHeight + CARD_GAP));
    }

    /** 上方留昵称槽；短气泡底边对齐头像，长内容从同一头像行向下展开。 */
    public static MessageRow messageRow(int contentHeight, int nameLineHeight) {
        int avatarY = nameLineHeight + 1;
        int contentY = avatarY + Math.max(0, AVATAR_SIZE - contentHeight);
        return new MessageRow(avatarY, contentY, avatarY + Math.max(AVATAR_SIZE, contentHeight));
    }

    public record MessageRow(int avatarY, int contentY, int height) {}

    /** 右边和下边不包含在命中区内，贴近的两个控件不能同时接到边界上的点击。 */
    public static boolean hit(double mx, double my, int x, int y, int width, int height) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }
}
