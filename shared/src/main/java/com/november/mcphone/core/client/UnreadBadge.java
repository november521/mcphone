package com.november.mcphone.core.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 主屏与通知共用的小型未读角标；数字按笔画区域而不是含留白的行框居中。 */
public final class UnreadBadge {
    private UnreadBadge() {}
    public static final int HEIGHT = 7;
    public static final float TEXT_SCALE = .6f;

    public record Ink(float width, float height) {}

    /** 原版数字最后一列是字间距，九像素行框中的数字笔画高七像素。 */
    public static Ink numericInk(int advance, int lineHeight, float scale) {
        return new Ink(Math.max(0, advance - 1) * scale, Math.max(1, lineHeight - 2) * scale);
    }

    public static int width(Font font, String label) {
        return Math.max(HEIGHT, (int) Math.ceil(numericInk(font.width(label), font.lineHeight, TEXT_SCALE).width()) + 4);
    }

    public static void draw(GuiGraphics g, Font font, String label, int x, int y) {
        int w = width(font, label);
        PhoneSkin.drawOrFill(g, PhoneSkin.Element.UNREAD_BADGE, x, y, w, HEIGHT, PhoneTheme.COLOR_UNREAD_BADGE);
        label(g, font, label, x + w / 2f, y + HEIGHT / 2f, TEXT_SCALE, PhoneTheme.FONT_COLOR_BADGE);
    }

    public static void label(GuiGraphics g, Font font, String label,
                             float centerX, float centerY, float scale, int color) {
        Ink ink = numericInk(font.width(label), font.lineHeight, scale);
        g.pose().pushPose();
        try {
            g.pose().translate(centerX - ink.width() / 2f, centerY - ink.height() / 2f, 0);
            g.pose().scale(scale, scale, 1f);
            g.drawString(font, label, 0, 0, color, false);
        } finally { g.pose().popPose(); }
    }
}
