package com.november.mcphone.feature.reader.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.feature.reader.BookRef;
import com.november.mcphone.feature.reader.client.compat.BookQuirks;
import com.november.mcphone.feature.reader.client.source.BookSources;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 书架页 —— 所有教程书列在这儿，点一本就翻开。
 *
 * 一行两句话：书名，以及它是哪个模组的
 *
 * 第二行写模组名而不是副标题，是因为几百个模组的整合包里，玩家找书的思路
 * 几乎总是"某某模组那本书"，而不是书名——很多书名还起得很花，光看名字
 * 认不出是谁的。
 *
 * 图标先问书源
 *
 * 画出来的是那本书自己的样子（多数情况就是那个书物品），玩家在仓库里见过它，
 * 一眼能认。书源画不出来时才退回我们自己那张书图，再没有才填纯色——
 * 与手机别处的换肤规矩一致。
 */
public final class BookList {

    private static final int PAD = 4;

    /** 行首那张小图，16 是物品图标的原生尺寸，别改成别的再去缩放 */
    private static final int ICON = 16;

    private int scrollOffset;
    private int hoveredIdx = -1;

    /** 待消费的"打开这本"请求，null 表示没有。与记事本一致：页面不自己跳转，交给 PhoneScreen */
    private BookRef pendingOpen;

    /**
     * 每次进来重扫一遍。
     *
     * 书的集合其实在模组加载完就定了，这一下几乎总是扫出同样的结果。仍然扫，
     * 是因为它便宜（几十本书的一次遍历），而万一将来有书源是会变的
     * （玩家自己传进来的书就会），少一次刷新就是一个"传了看不见"的 bug。
     */
    public void open() {
        scrollOffset = 0;
        hoveredIdx = -1;
        pendingOpen = null;
        BookSources.refreshAll();
    }

    public void close() {
        hoveredIdx = -1;
    }

    public BookRef consumeOpenRequest() {
        BookRef out = pendingOpen;
        pendingOpen = null;
        return out;
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;
        final int bottom = phoneTop + screenH - navH;
        int y = phoneTop + statusH + 4;

        List<BookRef> books = BookSources.allBooks();

        y = renderHeader(g, font, x, y, w, books.size());

        if (books.isEmpty()) {
            renderEmpty(g, font, x, y, w);
            hoveredIdx = -1;
            return;
        }

        clampScroll(books.size(), bottom - y, font);
        renderRows(g, font, books, x, y, w, bottom, mouseX, mouseY);
    }

    /** 标题右边写本数：书架有多满是一眼要看到的，也让"一本都没有"和"没加载出来"能分开 */
    private int renderHeader(GuiGraphics g, Font font, int x, int y, int w, int count) {
        g.drawString(font, Component.translatable("mcphone.app.reader").getString(),
                x, y, FontPalette.title(), true);

        String amount = Component.translatable("mcphone.reader.count", count).getString();
        g.drawString(font, amount, x + w - font.width(amount), y, FontPalette.subtle(), false);

        y += font.lineHeight + 4;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        return y + 4;
    }

    private void renderEmpty(GuiGraphics g, Font font, int x, int y, int w) {
        g.drawString(font, Component.translatable("mcphone.reader.empty").getString(),
                x, y, FontPalette.subtle(), false);
        y += font.lineHeight + 2;
        for (var line : font.split(Component.translatable("mcphone.reader.empty_hint"), w)) {
            g.drawString(font, line, x, y, FontPalette.dim(), false);
            y += font.lineHeight;
        }
    }

    private void renderRows(GuiGraphics g, Font font, List<BookRef> books,
                            int x, int y, int w, int bottom, int mouseX, int mouseY) {
        final int rowH = rowHeight(font);
        final int textX = x + ICON + 4;
        final int textW = w - ICON - 4;
        hoveredIdx = -1;

        for (int i = scrollOffset; i < books.size(); i++) {
            if (y + rowH > bottom) break;

            BookRef book = books.get(i);
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + rowH;
            if (hovered) {
                hoveredIdx = i;
                g.fill(x, y, x + w, y + rowH, PhoneTheme.COLOR_ROW_HOVER);
            }

            renderIcon(g, book, x, y + (rowH - ICON) / 2);

            g.drawString(font, GuiUtil.truncate(font, book.title().getString(), textW),
                    textX, y + 1, FontPalette.title(), false);

            String owner = book.owner() == null ? "" : book.owner();
            if (!owner.isEmpty()) {
                g.drawString(font, GuiUtil.truncate(font, owner, textW),
                        textX, y + font.lineHeight + 2, FontPalette.dim(), false);
            }

            y += rowH;
        }
    }

    /**
     * 图标一共四层，从最认得出的往最通用的退：
     * 特例（某个模组指定的那张图）→ 书源（那本书自己的物品）→ 换肤贴图 → 纯色。
     */
    private static void renderIcon(GuiGraphics g, BookRef book, int x, int y) {
        if (BookQuirks.renderIcon(g, book, x, y, ICON)) return;
        if (BookSources.renderIcon(g, book, x, y, ICON)) return;
        PhoneSkin.drawOrFill(g, PhoneSkin.Element.READER_BOOK, x, y, ICON, ICON,
                PhoneTheme.COLOR_BOOK_SPINE);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        List<BookRef> books = BookSources.allBooks();
        if (hoveredIdx >= 0 && hoveredIdx < books.size()) {
            pendingOpen = books.get(hoveredIdx);
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double scrollY) {
        if (scrollY > 0 && scrollOffset > 0) {
            scrollOffset--;
            return true;
        }
        if (scrollY < 0 && scrollOffset < BookSources.allBooks().size() - 1) {
            scrollOffset++;
            return true;
        }
        return false;
    }

    /** 两行字与一张 16 的图，取高的那个 */
    private static int rowHeight(Font font) {
        return Math.max(ICON + 2, font.lineHeight * 2 + 3);
    }

    /** 书变少时（卸了个模组、换了资源包）必须夹紧，否则会滚到空白处 */
    private void clampScroll(int total, int availableHeight, Font font) {
        int visible = Math.max(1, availableHeight / rowHeight(font));
        int maxOffset = Math.max(0, total - visible);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
        if (scrollOffset < 0) scrollOffset = 0;
    }
}
