package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneSkin;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

/** 聊天界面共用的字号与像素图标，沿用手机的字体配色和资源包气泡。 */
final class ChatUi {
    private ChatUi() {}

    enum Icon { BACK, MORE, VOICE, SMILE, PLUS, CHAT, CONTACTS, DISCOVER, PROFILE, SEARCH }

    static int accent() {
        return FontPalette.current().darkText() ? 0xFFA64A78 : 0xFFF3ACCE;
    }

    static void glass(GuiGraphics g, ChatGlass.Surface surface,
                      int x, int y, int w, int h, boolean hovered) {
        g.pose().pushPose();
        try {
            g.pose().scale(1f / ChatGlass.PRECISION, 1f / ChatGlass.PRECISION, 1f);
            // 玩家头部不裁圆，外框也用方角，与 MC 的方块像素保持一致。
            int radius = surface == ChatGlass.Surface.AVATAR ? 0 : 3;
            ChatGlass.paint(g::fill, x, y, w, h, radius,
                    ChatGlass.palette(surface, FontPalette.current().darkText(), hovered));
        } finally {
            g.pose().popPose();
        }
    }

    static int width(Font font, String text, float scale) {
        return ChatLayout.scaledWidth(font.width(text), scale);
    }

    static int lineHeight(Font font, float scale) {
        return ChatLayout.scaledWidth(font.lineHeight, scale);
    }

    static String truncate(Font font, String text, int width, float scale) {
        return GuiUtil.truncate(font, text, ChatLayout.unscaledWidth(width, scale));
    }

    static void text(GuiGraphics g, Font font, String text, float x, float y, float scale, int color) {
        text(g, font, net.minecraft.network.chat.Component.literal(text).getVisualOrderText(),
                x, y, scale, color);
    }

    static void text(GuiGraphics g, Font font, FormattedCharSequence text,
                     float x, float y, float scale, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    /** 用未取整的字宽居中，避免小字号时向左偏半个逻辑像素。 */
    static void centeredText(GuiGraphics g, Font font, String text,
                             float centerX, float y, float scale, int color) {
        g.pose().pushPose();
        g.pose().translate(centerX - font.width(text) * scale / 2f, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    /** 原版字体的行尾字间距与底部两像素留白不参与按钮文字的视觉居中。 */
    static void buttonText(GuiGraphics g, Font font, String text,
                           float centerX, float centerY, float scale, int color) {
        float inkWidth = Math.max(0, font.width(text) - 1) * scale;
        float inkHeight = Math.max(1, font.lineHeight - 2) * scale;
        text(g, font, text, centerX - inkWidth / 2f, centerY - inkHeight / 2f, scale, color);
    }

    static void centeredIcon(GuiGraphics g, Icon icon, float centerX, int y, int color) {
        g.pose().pushPose();
        g.pose().translate(centerX - ChatLayout.ICON_SIZE / 2f, y, 0);
        icon(g, icon, 0, 0, color);
        g.pose().popPose();
    }

    /** 首页摘要始终单行；只处理显示文本，不改变存储的原消息。 */
    static void preview(GuiGraphics g, Font font, String text, int x, float y, int width, int color) {
        if (width <= 0) return;
        String singleLine = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        text(g, font, truncate(font, singleLine, width, ChatLayout.META_SCALE), x, y,
                ChatLayout.META_SCALE, color);
    }

    /** 按钮与透明面板使用同一套细分圆角，字号和命中区保持逻辑像素。 */
    static void roundedButton(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.pose().pushPose();
        try {
            g.pose().scale(1f / ChatGlass.PRECISION, 1f / ChatGlass.PRECISION, 1f);
            ChatGlass.paintSolid(g::fill, x, y, w, h, 2, color);
        } finally {
            g.pose().popPose();
        }
    }

    static void sizedIcon(GuiGraphics g, Icon icon, float x, float y, int size, int color) {
        g.pose().pushPose();
        try {
            g.pose().translate(x, y, 0);
            g.pose().scale(size / (float) ChatLayout.ICON_SIZE, size / (float) ChatLayout.ICON_SIZE, 1f);
            icon(g, icon, 0, 0, color);
        } finally {
            g.pose().popPose();
        }
    }

    /** 优先使用用户提供的透明贴图；没有对应素材或资源包删除贴图时才画几何兜底。 */
    static void icon(GuiGraphics g, Icon icon, int x, int y, int color) {
        PhoneSkin.Element texture = switch (icon) {
            case CHAT -> PhoneSkin.Element.CHAT_TAB_MESSAGE;
            case CONTACTS -> PhoneSkin.Element.CHAT_TAB_CONTACTS;
            case DISCOVER -> PhoneSkin.Element.CHAT_TAB_DISCOVER;
            case PROFILE -> PhoneSkin.Element.CHAT_TAB_PROFILE;
            case SEARCH -> PhoneSkin.Element.CHAT_SEARCH;
            case PLUS -> PhoneSkin.Element.CHAT_ATTACH;
            case BACK -> PhoneSkin.Element.CHAT_BACK;
            case MORE -> PhoneSkin.Element.CHAT_MORE;
            case SMILE -> PhoneSkin.Element.CHAT_SMILE;
            case VOICE -> PhoneSkin.Element.CHAT_VOICE;
        };
        if (texture != null) {
            // 白色源图乘当前颜色，保留粉色强调色和禁用项灰色；画完必须还原，避免给后续文字染色。
            g.setColor(((color >>> 16) & 255) / 255f, ((color >>> 8) & 255) / 255f,
                    (color & 255) / 255f, ((color >>> 24) & 255) / 255f);
            boolean drawn;
            try {
                drawn = PhoneSkin.draw(g, texture, x, y, ChatLayout.ICON_SIZE, ChatLayout.ICON_SIZE);
            } finally {
                g.setColor(1f, 1f, 1f, 1f);
            }
            if (drawn) return;
        }
        switch (icon) {
            case BACK -> {
                // 在 24 像素坐标中画细箭头再缩放，避免旧版 9 像素台阶过粗、重心贴左。
                g.pose().pushPose();
                g.pose().translate(x, y, 0);
                g.pose().scale(ChatLayout.ICON_SIZE / 24f, ChatLayout.ICON_SIZE / 24f, 1f);
                for (int i = 0; i < 9; i++) {
                    g.fill(14 - i, 3 + i, 16 - i, 5 + i, color);
                    g.fill(14 - i, 19 - i, 16 - i, 21 - i, color);
                }
                g.pose().popPose();
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
