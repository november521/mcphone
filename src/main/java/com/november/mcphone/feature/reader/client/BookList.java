package com.november.mcphone.feature.reader.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.feature.reader.BookRef;
import com.november.mcphone.feature.reader.BookSearch;
import com.november.mcphone.feature.reader.client.compat.BookQuirks;
import com.november.mcphone.feature.reader.client.source.BookSources;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
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
 *
 * 搜索框一直有焦点，进来就能打字
 *
 * 顶上那一栏不是"点了才能输入"的框——进这一页它就已经拿着焦点了，玩家想找哪本
 * 直接打字，省掉"先点一下框"这一步。代价是这一页会吃掉所有按键（包括背包键），
 * 与记事本、改设备名那几页一样：打拼音必然按到 e，不吃掉就成了开背包。ESC 照旧
 * 直接关机，那是全局规矩，不在这里破例。
 *
 * 筛出来的结果按分数排，算术在 {@link BookSearch} 里
 */
public final class BookList {

    private static final int PAD = 4;

    /** 行首那张小图，16 是物品图标的原生尺寸，别改成别的再去缩放 */
    private static final int ICON = 16;

    /** 搜索栏高度。12 是"一行字加上下各一点余量"，再高就要吃掉一整行书 */
    private static final int SEARCH_H = 12;

    /** 搜索栏里文字离左右边的距离 */
    private static final int SEARCH_PAD = 3;

    /** 查询长度上限。搜索是找前几个字，不是抄书名 */
    private static final int MAX_QUERY = 48;

    /** 搜索框。首次渲染时才建得出来——那时候才知道机身在屏幕的哪个位置 */
    private EditBox search;

    private int scrollOffset;
    private int hoveredIdx = -1;

    /** 待消费的"打开这本"请求，null 表示没有。与记事本一致：页面不自己跳转，交给 PhoneScreen */
    private BookRef pendingOpen;

    /** 上一次筛出来的结果，以及算它用的那两个输入。见 {@link #filtered} */
    private List<BookRef> filteredBooks = List.of();
    private List<BookRef> filteredFrom;
    private String filteredQuery;

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

        // 每次进来都从"没在搜"开始。上次搜过什么是上次的事，留着它等于
        // 一进书架就看见一份筛过的书目，而玩家多半以为这就是全部
        if (search != null) search.setValue("");
        forgetFiltered();

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
                       int mouseX, int mouseY, float partialTick, Font font) {

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;
        final int bottom = phoneTop + screenH - navH;
        int y = phoneTop + statusH + 4;

        List<BookRef> all = BookSources.allBooks();
        List<BookRef> books = filtered(all);

        y = renderSearchBar(g, font, x, y, w, all.size(), books.size(),
                mouseX, mouseY, partialTick);

        if (books.isEmpty()) {
            renderEmpty(g, font, x, y, w, all.isEmpty());
            hoveredIdx = -1;
            return;
        }

        clampScroll(books.size(), bottom - y, font);
        renderRows(g, font, books, x, y, w, bottom, mouseX, mouseY);
    }

    /**
     * 顶上那一栏：搜索框 + 右边的本数。
     *
     * 两样挤在同一行，是因为这块屏幕只有 200 高，各占一行就要少显示一整本书。
     * 搜的时候本数变成"命中/总数"，那正是搜的人想知道的：筛掉了多少。
     */
    private int renderSearchBar(GuiGraphics g, Font font, int x, int y, int w,
                                int total, int matched,
                                int mouseX, int mouseY, float partialTick) {

        String count = countText(total, matched);
        int countW = font.width(count);
        int barW = Math.max(SEARCH_H, w - countW - 4);

        PhoneSkin.drawOrFill(g, PhoneSkin.Element.READER_SEARCH_BAR,
                x, y, barW, SEARCH_H, PhoneTheme.COLOR_SEARCH_BAR);

        // 无边框的 EditBox 不会自己垂直居中，手动摆到栏中间（与会话页那条输入栏同一算法）
        int textY = y + (SEARCH_H - font.lineHeight) / 2 + 1;
        int textW = barW - SEARCH_PAD * 2;

        if (search == null) {
            search = new EditBox(font, x + SEARCH_PAD, textY, textW, SEARCH_H - 4,
                    Component.translatable("mcphone.reader.search"));
            search.setMaxLength(MAX_QUERY);
            search.setBordered(false);
            search.setFocused(true);
        } else {
            search.setX(x + SEARCH_PAD);
            search.setY(textY);
            search.setWidth(textW);
        }

        // 占位提示走 suggestion 而不是 hint：hint 只在【没有焦点】时画，
        // 而这个框一直握着焦点（进来就能打字），用 hint 等于永远看不见提示
        search.setSuggestion(search.getValue().isEmpty()
                ? Component.translatable("mcphone.reader.search").getString()
                : null);
        search.render(g, mouseX, mouseY, partialTick);

        g.drawString(font, count, x + w - countW, textY, FontPalette.subtle(), false);

        y += SEARCH_H + 3;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        return y + 4;
    }

    /** 没在搜就是"共几本"，在搜就是"命中/总数" */
    private String countText(int total, int matched) {
        return query().isBlank()
                ? Component.translatable("mcphone.reader.count", total).getString()
                : Component.translatable("mcphone.reader.count_filtered", matched, total).getString();
    }

    /**
     * 空的时候要说清是哪一种空：一本书都没有，和搜不到，玩家该做的事完全不同。
     * 前者是"这个整合包没有用手册的模组"，后者是"换个词再试"。
     */
    private void renderEmpty(GuiGraphics g, Font font, int x, int y, int w, boolean shelfEmpty) {
        String title = shelfEmpty ? "mcphone.reader.empty" : "mcphone.reader.no_match";
        String hint = shelfEmpty ? "mcphone.reader.empty_hint" : "mcphone.reader.no_match_hint";

        g.drawString(font, Component.translatable(title).getString(),
                x, y, FontPalette.subtle(), false);
        y += font.lineHeight + 2;
        for (var line : font.split(Component.translatable(hint), w)) {
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

        // 先给搜索框：点栏里是移光标，不该被下面的行判定吃掉
        if (search != null && search.mouseClicked(mx, my, button)) return true;

        List<BookRef> books = filtered(BookSources.allBooks());
        if (hoveredIdx >= 0 && hoveredIdx < books.size()) {
            pendingOpen = books.get(hoveredIdx);
            return true;
        }
        return false;
    }

    /**
     * 按键全部交给搜索框。
     *
     * 无论消费与否，PhoneScreen 那边都会把这一页的按键整个吃掉——打拼音必然
     * 按到 e，而背包键默认就是 e，不吃掉就成了"搜着搜着背包开了"。
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return search != null && search.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 文字（含输入法提交与粘贴）进入搜索框的唯一通道 */
    public boolean charTyped(char c, int modifiers) {
        return search != null && search.charTyped(c, modifiers);
    }

    public boolean mouseScrolled(double scrollY) {
        if (scrollY > 0 && scrollOffset > 0) {
            scrollOffset--;
            return true;
        }
        if (scrollY < 0 && scrollOffset < filtered(BookSources.allBooks()).size() - 1) {
            scrollOffset++;
            return true;
        }
        return false;
    }

    //  搜索

    private String query() {
        return search == null ? "" : search.getValue();
    }

    /**
     * 当前该显示的那些书。
     *
     * 结果缓存着，只在【查询变了】或【书目被重扫过】时重算。界面每帧都要问一次，
     * 而每帧给几十本书各算一次分、再排一次序，算出来的东西一帧之内就扔掉——
     * 音乐那边的曲库就栽在这种地方（见 BookSources.allBooks 的注释）。
     *
     * 判"书目变没变"用的是引用相等：{@link BookSources#allBooks()} 在重扫之前
     * 一直返回同一个 List 实例，所以 == 就够，不必逐本比。
     */
    private List<BookRef> filtered(List<BookRef> all) {
        String q = query();
        if (all == filteredFrom && q.equals(filteredQuery)) return filteredBooks;

        filteredFrom = all;
        filteredQuery = q;
        filteredBooks = select(all, q);

        // 换了查询就回到顶部：接着上一次的滚动位置看一份新结果，很像"搜出来是空的"
        scrollOffset = 0;

        return filteredBooks;
    }

    /** 命中的书，按分数从高到低。同分保持书架原来的顺序（List.sort 是稳定排序） */
    private static List<BookRef> select(List<BookRef> all, String query) {
        if (query.isBlank()) return all;

        List<Scored> hits = new ArrayList<>();
        for (BookRef book : all) {
            int score = BookSearch.score(query,
                    book.title().getString(), book.owner(), book.bookId().toString());
            if (score != BookSearch.NO_MATCH) hits.add(new Scored(book, score));
        }

        hits.sort(Comparator.comparingInt(Scored::score).reversed());

        List<BookRef> out = new ArrayList<>(hits.size());
        for (Scored hit : hits) out.add(hit.book());
        return List.copyOf(out);
    }

    /** 排序用的一对：书与它这次的得分。分数只算一次，别放进比较器里现算 */
    private record Scored(BookRef book, int score) {}

    /** 忘掉上一次筛的结果，下次渲染重算 */
    private void forgetFiltered() {
        filteredFrom = null;
        filteredQuery = null;
        filteredBooks = List.of();
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
