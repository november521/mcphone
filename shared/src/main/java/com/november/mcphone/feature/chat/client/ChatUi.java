package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

/** 聊天界面共用的字号与像素图标，沿用手机的字体配色和资源包气泡。 */
final class ChatUi {
    private ChatUi() {}

    enum Icon { BACK, MORE, VOICE, SMILE, PLUS, CHAT, CONTACTS, DISCOVER, PROFILE, SEARCH }

    static int width(Font font, String text, float scale) {
        return ChatLayout.scaledWidth(font.width(text), scale);
    }

    static int lineHeight(Font font, float scale) {
        return ChatLayout.scaledWidth(font.lineHeight, scale);
    }

    static String truncate(Font font, String text, int width, float scale) {
        return GuiUtil.truncate(font, text, ChatLayout.unscaledWidth(width, scale));
    }

    static void text(GuiGraphics g, Font font, String text, int x, int y, float scale, int color) {
        text(g, font, net.minecraft.network.chat.Component.literal(text).getVisualOrderText(),
                x, y, scale, color);
    }

    static void text(GuiGraphics g, Font font, FormattedCharSequence text,
                     int x, int y, float scale, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    /** 固定 9×9 的图形，不依赖字体是否带有语音、表情等 Unicode 字形。 */
    static void icon(GuiGraphics g, Icon icon, int x, int y, int color) {
        switch (icon) {
            case BACK -> {
                for (int i = 0; i < 5; i++) {
                    g.fill(x + 5 - i, y + i, x + 6 - i, y + i + 1, color);
                    g.fill(x + 5 - i, y + 8 - i, x + 6 - i, y + 9 - i, color);
                }
            }
            case MORE -> {
                for (int i = 0; i < 3; i++) g.fill(x + i * 3, y + 4, x + i * 3 + 2, y + 6, color);
            }
            case PLUS, SMILE, VOICE, DISCOVER, SEARCH -> {
                ring(g, x, y, color);
                switch (icon) {
                    case PLUS -> {
                        g.fill(x + 2, y + 4, x + 7, y + 5, color);
                        g.fill(x + 4, y + 2, x + 5, y + 7, color);
                    }
                    case SMILE -> {
                        g.fill(x + 2, y + 3, x + 3, y + 4, color);
                        g.fill(x + 6, y + 3, x + 7, y + 4, color);
                        g.fill(x + 3, y + 6, x + 6, y + 7, color);
                    }
                    case VOICE -> {
                        g.fill(x + 3, y + 3, x + 4, y + 6, color);
                        g.fill(x + 5, y + 2, x + 6, y + 7, color);
                    }
                    case DISCOVER -> {
                        g.fill(x + 5, y + 2, x + 6, y + 4, color);
                        g.fill(x + 3, y + 4, x + 5, y + 6, color);
                    }
                    case SEARCH -> g.fill(x + 7, y + 7, x + 9, y + 9, color);
                    default -> { }
                }
            }
            case CHAT -> {
                g.renderOutline(x, y + 1, 9, 6, color);
                g.fill(x + 2, y + 7, x + 3, y + 9, color);
            }
            case CONTACTS, PROFILE -> {
                g.renderOutline(x + 3, y, 3, 4, color);
                g.renderOutline(x + 1, y + 5, 7, 4, color);
                if (icon == Icon.CONTACTS) g.fill(x + 7, y + 2, x + 9, y + 3, color);
            }
        }
    }

    private static void ring(GuiGraphics g, int x, int y, int color) {
        g.fill(x + 2, y, x + 7, y + 1, color);
        g.fill(x + 2, y + 8, x + 7, y + 9, color);
        g.fill(x, y + 2, x + 1, y + 7, color);
        g.fill(x + 8, y + 2, x + 9, y + 7, color);
        g.fill(x + 1, y + 1, x + 2, y + 2, color);
        g.fill(x + 7, y + 1, x + 8, y + 2, color);
        g.fill(x + 1, y + 7, x + 2, y + 8, color);
        g.fill(x + 7, y + 7, x + 8, y + 8, color);
    }
}
