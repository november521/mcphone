package com.november.mcphone.feature.settings.client.changelog;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.FontPreset;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.ArrayList;
import java.util.List;

/** 弹窗和历史页共用正文；标题、关闭按钮固定，只有正文滚动。 */
public final class ChangelogPage {
    private List<Changelog.Release> releases = List.of();
    private static final int CLOSE_INSET = 3;

    private int scroll, maxScroll;
    private int left, top, width, bottom, textTop, textBottom;
    private boolean modal;
    private List<Row> rows = List.of();
    private Font layoutFont;
    private int layoutWidth;
    private String layoutLanguage;
    private FontPreset layoutPalette;
    private final List<LinkHit> links = new ArrayList<>();
    private record LinkHit(int x, int y, int width, int height, String url) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + width && my >= y && my < y + height; }
    }
    private record Row(FormattedCharSequence text, int color, String url) {}

    public void open(List<Changelog.Release> values) {
        releases = values;
        scroll = maxScroll = 0;
        layoutFont = null;
        rows = List.of();
        links.clear();
    }
    public void render(GuiGraphics g, int x, int y, int w, int h, int statusH, int navH,
                       Font font, boolean popup, double mouseX, double mouseY) {
        modal = popup;
        left = x + (popup ? 6 : 4);
        top = y + statusH + (popup ? 8 : 4);
        width = w - (left - x) * 2;
        bottom = y + h - navH - (popup ? 8 : 2);
        if (popup) {
            g.fill(x, y, x + w, y + h, PhoneTheme.COLOR_OVERLAY);
            g.fill(left - 1, top - 1, left + width + 1, bottom + 1, PhoneTheme.COLOR_DIVIDER);
            g.fill(left, top, left + width, bottom,
                    FontPalette.current().darkText() ? 0xFFF4F1F5 : 0xFF29252F);
        }
        g.drawString(font, Component.translatable("mcphone.changelog.title"), left + 4, top + 3, FontPalette.title(), false);
        textTop = top + font.lineHeight + 9;
        textBottom = bottom - (popup ? font.lineHeight + 12 : 3);
        int textWidth = width - 12;
        layout(font, textWidth, Minecraft.getInstance().options.languageCode);
        int lineH = font.lineHeight + 2;
        maxScroll = Math.max(0, rows.size() * lineH - (textBottom - textTop));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        links.clear();
        GuiUtil.enableScissor(g, left + 3, textTop, left + width - 3, textBottom);
        try {
            int first = scroll / lineH;
            int end = Math.min(rows.size(), (scroll + textBottom - textTop + lineH - 1) / lineH);
            for (int i = first; i < end; i++) {
                Row row = rows.get(i);
                int rowY = textTop + i * lineH - scroll;
                if (rowY + lineH <= textTop || rowY >= textBottom) continue;
                g.drawString(font, row.text(), left + 5, rowY, row.color(), false);
                if (row.url() != null) links.add(new LinkHit(left + 5, Math.max(textTop, rowY),
                        Math.min(textWidth, font.width(row.text())), Math.min(textBottom, rowY + lineH) - Math.max(textTop, rowY), row.url()));
            }
        } finally { g.disableScissor(); }
        if (maxScroll > 0) {
            int track = textBottom - textTop;
            int thumb = Math.max(8, track * track / (track + maxScroll));
            int thumbY = textTop + (track - thumb) * scroll / maxScroll;
            g.fill(left + width - 3, textTop, left + width - 2, textBottom, PhoneTheme.COLOR_DIVIDER_FAINT);
            g.fill(left + width - 3, thumbY, left + width - 2, thumbY + thumb, FontPalette.subtle());
        }
        if (popup) {
            drawCloseButton(g, font, mouseX, mouseY);
        }
    }
    private void drawCloseButton(GuiGraphics g, Font font, double mouseX, double mouseY) {
        int x = left + CLOSE_INSET;
        int y = textBottom + CLOSE_INSET;
        int w = width - CLOSE_INSET * 2;
        int h = bottom - textBottom - CLOSE_INSET * 2;
        boolean hovered = hitsCloseButton(mouseX, mouseY);
        boolean darkText = FontPalette.current().darkText();
        int background = darkText
                ? (hovered ? 0x30000000 : 0x14000000)
                : (hovered ? 0x55FFFFFF : PhoneTheme.COLOR_DIVIDER_FAINT);
        g.fill(x, y, x + w, y + h, background);
        if (hovered) g.renderOutline(x, y, w, h, darkText ? 0x55000000 : 0x66FFFFFF);

        String close = Component.translatable("mcphone.changelog.close").getString();
        g.drawString(font, close, x + (w - font.width(close)) / 2, y + (h - font.lineHeight) / 2,
                hovered ? FontPalette.title() : FontPalette.body(), false);
    }

    private boolean hitsCloseButton(double mouseX, double mouseY) {
        // fill 的右、下坐标不属于按钮，悬停与点击都沿用这个范围。
        return modal && mouseX >= left + CLOSE_INSET && mouseX < left + width - CLOSE_INSET
                && mouseY >= textBottom + CLOSE_INSET && mouseY < bottom - CLOSE_INSET;
    }

    /** 正文只在打开、宽度、语言、字体或配色变化时重排，滚动不重新解析历史。 */
    private void layout(Font font, int textWidth, String language) {
        if (layoutFont == font && layoutWidth == textWidth && language.equals(layoutLanguage)
                && layoutPalette == FontPalette.current()) return;
        var next = new ArrayList<Row>();
        if (releases.isEmpty()) add(next, font, Component.translatable("mcphone.changelog.empty").getString(), textWidth, FontPalette.subtle(), null);
        for (Changelog.Release release : releases) {
            String version = release.version().equals("next")
                    ? Component.translatable("mcphone.changelog.upcoming").getString() : "v" + release.version();
            add(next, font, version, textWidth, FontPalette.title(), null);
            if (!release.date().isBlank()) add(next, font, release.date(), textWidth, FontPalette.subtle(), null);
            for (Changelog.Section section : release.sections(language)) {
                add(next, font, section.title(), textWidth, FontPalette.notice(), null);
                for (String source : section.entries()) {
                    var entry = ChangelogText.parse(source);
                    add(next, font, "• " + entry.text(), textWidth, FontPalette.body(), null);
                    for (var link : entry.links()) add(next, font, "↗ " + link.label(), textWidth, FontPalette.link(), link.url());
                    next.add(new Row(FormattedCharSequence.EMPTY, 0, null));
                }
            }
            next.add(new Row(FormattedCharSequence.EMPTY, 0, null));
        }
        rows = List.copyOf(next);
        layoutFont = font;
        layoutWidth = textWidth;
        layoutLanguage = language;
        layoutPalette = FontPalette.current();
        links.clear();
    }
    private static void add(List<Row> rows, Font font, String text, int width, int color, String url) {
        for (var line : font.split(Component.literal(text), width)) rows.add(new Row(line, color, url));
    }
    /** 返回是否点中关闭按钮；其他点击全部由手机的模态层吞掉。 */
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        if (hitsCloseButton(mx, my)) return true;
        for (var link : links) if (link.contains(mx, my)) {
            net.minecraft.Util.getPlatform().openUri(link.url());
            break;
        }
        return false;
    }
    public void scroll(double mx, double my, double amount) {
        if (mx >= left && mx < left + width && my >= textTop && my < textBottom) {
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (amount * 24)));
            links.clear(); // 下一帧重建命中区域，不能点击滚动前残留的旧链接。
        }
    }
}
