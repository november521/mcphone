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
 * 阅读 App 的书页 —— 两页共用一套排版：书架与书城。
 *
 * 书架与书城的分工
 *
 * 书城是整合包里【有】的全部书，几十本；书架是玩家自己收下的那几本。常翻的书
 * 通常不超过五六本，书架存在的全部理由就是把它们从几十本里摘出来。收藏靠每行
 * 右端那颗 ☆，点一下进书架，再点一下拿出来——不长按、不另开菜单：长按在这种
 * 小屏上没有任何提示，等于把功能藏起来。
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
 * 停在哪一页记在 static 上
 *
 * 手机每开一次就新建一个 PhoneScreen，也就新建一个本类；页签记成实例字段的话，
 * 从书城点开一本书、看完回来就又回到书架，每看一本都要重点一次「书城」。所以它
 * 是 static：一局之内记着。首次进入是书架，那是刻意的默认值。
 *
 * 筛出来的结果按分数排，算术在 {@link BookSearch} 里
 */
public final class BookList {

    private static final int PAD = 4;

    /** 行首那张小图，16 是物品图标的原生尺寸，别改成别的再去缩放 */
    private static final int ICON = 16;

    /** 行右端那颗星。与音乐页那几个键一样大，它们是同一家的 */
    private static final int STAR = 9;

    /** 底部切换栏高度。它吃掉的正好是一整行书：可见行数因此从 7 变成 6 */
    private static final int TAB_H = 12;

    /** 小目标的命中区四边各放宽多少。手机是缩放显示的，正好按字形边界算会点不中 */
    private static final int HIT_PAD = 2;

    /** 搜索栏高度。12 是"一行字加上下各一点余量"，再高就要吃掉一整行书 */
    private static final int SEARCH_H = 12;

    /** 搜索栏里文字离左右边的距离 */
    private static final int SEARCH_PAD = 3;

    /** 查询长度上限。搜索是找前几个字，不是抄书名 */
    private static final int MAX_QUERY = 48;

    /** 两页 */
    public enum Tab { SHELF, STORE }

    /** 停在哪一页。static 的理由见类注释；默认书架 */
    private static Tab tab = Tab.SHELF;

    /** 搜索框。首次渲染时才建得出来——那时候才知道机身在屏幕的哪个位置 */
    private EditBox search;

    private int scrollOffset;
    private int hoveredIdx = -1;

    /** 悬停在哪一行的 ☆ 上，-1 表示没有。它与整行的命中区重叠，点击时必须先问它 */
    private int starHoveredIdx = -1;

    /** 悬停在底部哪个页签上，null 表示没有 */
    private Tab hoveredTab;

    /** 待消费的"打开这本"请求，null 表示没有。与记事本一致：页面不自己跳转，交给 PhoneScreen */
    private BookRef pendingOpen;

    /** 当前页该列哪些书（还没过搜索），以及算它用的三个输入。见 {@link #base} */
    private List<BookRef> baseBooks = List.of();
    private List<BookRef> baseFrom;
    private Tab baseTab;
    private int baseRevision = -1;

    /**
     * 上一次因为"换了搜索词"而把列表拉回顶部时，用的是哪个词。
     *
     * 单独记一个，而不是拿筛选缓存那几个字段兼职：底表也会让筛选结果作废
     * （收藏一本书就会），而那时候【不该】动滚动位置——滚到第二十本去点个 ☆，
     * 列表却弹回第一本，是很难忍的。只有搜索词真的变了才归位。
     */
    private String scrollQuery = "";

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
     *
     * 【不】重置页签：停在哪一页是跨开机记着的，理由见类注释。
     */
    public void open() {
        scrollOffset = 0;
        hoveredIdx = -1;
        starHoveredIdx = -1;
        hoveredTab = null;
        pendingOpen = null;

        // 每次进来都从"没在搜"开始。上次搜过什么是上次的事，留着它等于
        // 一进来就看见一份筛过的书目，而玩家多半以为这就是全部
        if (search != null) search.setValue("");
        scrollQuery = "";
        forgetFiltered();

        BookSources.refreshAll();
    }

    public void close() {
        hoveredIdx = -1;
        starHoveredIdx = -1;
        hoveredTab = null;
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

        // 切换栏贴着导航栏上沿摆，书目区的下沿再往上让出分隔线与空隙
        final int tabY = phoneTop + screenH - navH - TAB_H - 2;
        final int bottom = tabY - 5;

        int y = phoneTop + statusH + 4;

        List<BookRef> all = BookSources.allBooks();
        List<BookRef> base = base(all);
        List<BookRef> books = filtered(base);

        y = renderSearchBar(g, font, x, y, w, base.size(), books.size(),
                mouseX, mouseY, partialTick);

        if (books.isEmpty()) {
            renderEmpty(g, font, x, y, w, base.isEmpty());
            hoveredIdx = -1;
            starHoveredIdx = -1;
        } else {
            clampScroll(books.size(), bottom - y, font);
            renderRows(g, font, books, x, y, w, bottom, mouseX, mouseY);
        }

        renderTabs(g, font, x, tabY, w, mouseX, mouseY);
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
        // 而这个框一直握着焦点（进来就能打字），用 hint 等于永远看不见提示。
        //
        // 必须自己截断：EditBox 画 suggestion 那一句没有任何裁剪（正文有
        // plainSubstrByWidth，suggestion 没有），放不下就直接画出框外，糊到
        // 右边的本数上。中文那句只有 54 像素看不出来，英文的一百多像素一眼就露
        search.setSuggestion(search.getValue().isEmpty()
                ? GuiUtil.truncate(font, Component.translatable("mcphone.reader.search").getString(),
                        textW - 2)
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
     * 空的时候要说清是哪一种空，三种话该说的完全不一样：
     *
     *   书架上没有  —— 该去书城收几本，得告诉他 ☆ 在哪
     *   书城里没有  —— 这个整合包没有用手册的模组，玩家做什么都没用
     *   搜不到      —— 换个词再试
     */
    private void renderEmpty(GuiGraphics g, Font font, int x, int y, int w, boolean baseEmpty) {
        String title;
        String hint;
        if (!baseEmpty) {
            title = "mcphone.reader.no_match";
            hint = "mcphone.reader.no_match_hint";
        } else if (tab == Tab.SHELF) {
            title = "mcphone.reader.shelf_empty";
            hint = "mcphone.reader.shelf_empty_hint";
        } else {
            title = "mcphone.reader.empty";
            hint = "mcphone.reader.empty_hint";
        }

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
        final int starX = x + w - STAR;
        final int textW = starX - textX - 3;
        hoveredIdx = -1;
        starHoveredIdx = -1;

        for (int i = scrollOffset; i < books.size(); i++) {
            if (y + rowH > bottom) break;

            BookRef book = books.get(i);
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + rowH;
            if (hovered) {
                hoveredIdx = i;
                g.fill(x, y, x + w, y + rowH, PhoneTheme.COLOR_ROW_HOVER);
            }

            // ☆ 的命中区落在整行里面，所以两个都要记，点击时先问星那个
            boolean onStar = mouseX >= starX - HIT_PAD && mouseX <= x + w
                          && mouseY >= y && mouseY < y + rowH;
            if (onStar) starHoveredIdx = i;

            renderIcon(g, book, x, y + (rowH - ICON) / 2);
            renderStar(g, font, starX, y + (rowH - STAR) / 2,
                    ShelfStore.contains(book), onStar);

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

    /**
     * 行右端那颗星：实心＝在书架上，空心＝不在。
     *
     * 贴图优先，缺图画 ★ / ☆ 两个字符——与相册那个删除键同一套路数：两态的形状
     * 本来就不一样，不能靠给同一张图变色来区分。
     */
    private static void renderStar(GuiGraphics g, Font font, int x, int y,
                                   boolean shelved, boolean hovered) {
        PhoneSkin.Element element = shelved
                ? PhoneSkin.Element.READER_SHELVED
                : PhoneSkin.Element.READER_UNSHELVED;

        if (PhoneSkin.has(element)) {
            if (hovered) {
                g.fill(x - HIT_PAD, y - HIT_PAD, x + STAR + HIT_PAD, y + STAR + HIT_PAD,
                        PhoneTheme.COLOR_HOVER_STRONG);
            }
            PhoneSkin.draw(g, element, x, y, STAR, STAR);
            return;
        }

        String glyph = shelved ? "★" : "☆";
        int color = shelved ? FontPalette.armed()
                : (hovered ? FontPalette.title() : FontPalette.dim());
        g.drawString(font, glyph, x + (STAR - font.width(glyph)) / 2, y, color, false);
    }

    /**
     * 底部的两个页签。
     *
     * 摆在底部而不是顶部：顶上那一行给了搜索框，它一直握着焦点，两样挤一起
     * 既没地方也容易误触；而底部紧挨着导航栏，本来就是这块屏幕上手最熟的一带。
     */
    private void renderTabs(GuiGraphics g, Font font, int x, int y, int w,
                            int mouseX, int mouseY) {
        g.fill(x, y - 4, x + w, y - 3, PhoneTheme.COLOR_DIVIDER);

        hoveredTab = null;
        int half = w / 2;
        drawTab(g, font, x, y, half, Tab.SHELF, "mcphone.reader.tab_shelf", mouseX, mouseY);
        drawTab(g, font, x + half, y, w - half, Tab.STORE, "mcphone.reader.tab_store",
                mouseX, mouseY);
    }

    /** 当前那一页画个底，另一页只有字。悬停时字提亮一档，让人知道点得动 */
    private void drawTab(GuiGraphics g, Font font, int x, int y, int w, Tab which,
                         String key, int mouseX, int mouseY) {

        boolean active = tab == which;
        boolean hovered = GuiUtil.hit(mouseX, mouseY, x, y, w, TAB_H);
        if (hovered && !active) hoveredTab = which;

        if (active) {
            PhoneSkin.drawOrFill(g, PhoneSkin.Element.READER_TAB, x, y, w, TAB_H,
                    PhoneTheme.COLOR_READER_TAB);
        }

        String label = GuiUtil.truncate(font, Component.translatable(key).getString(), w - 4);
        int color = active ? FontPalette.title()
                : (hovered ? FontPalette.body() : FontPalette.dim());
        g.drawString(font, label, x + (w - font.width(label)) / 2,
                y + (TAB_H - font.lineHeight) / 2 + 1, color, false);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        // 先给搜索框：点栏里是移光标，不该被下面的行判定吃掉
        if (search != null && search.mouseClicked(mx, my, button)) return true;

        if (hoveredTab != null) {
            switchTo(hoveredTab);
            return true;
        }

        List<BookRef> books = filtered(base(BookSources.allBooks()));

        // ☆ 的命中区落在整行里面，必须先判它，否则点收藏会变成打开这本书
        if (starHoveredIdx >= 0 && starHoveredIdx < books.size()) {
            ShelfStore.toggle(books.get(starHoveredIdx));
            return true;
        }

        if (hoveredIdx >= 0 && hoveredIdx < books.size()) {
            pendingOpen = books.get(hoveredIdx);
            return true;
        }
        return false;
    }

    /** 换一页。搜索词跟着清掉：带着"新生"这个词切到书架，看见空列表会以为书架是空的 */
    private void switchTo(Tab target) {
        if (tab == target) return;

        tab = target;
        scrollOffset = 0;
        hoveredIdx = -1;
        starHoveredIdx = -1;
        if (search != null) search.setValue("");
        scrollQuery = "";
        forgetFiltered();
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
        if (scrollY < 0
                && scrollOffset < filtered(base(BookSources.allBooks())).size() - 1) {
            scrollOffset++;
            return true;
        }
        return false;
    }

    //  当前页该列哪些书

    /**
     * 底表：书架页是收藏的那些，书城页是全部。
     *
     * 三个输入任何一个变了都要重拼：换了页签、书源重扫过、或者玩家点了 ☆。
     * 最后那个靠 {@link ShelfStore#revision()} 认——在书架页上取消一本收藏，
     * 这张表会当场少一行，不重拼就会点到已经不在架上的那一本。
     *
     * 缓存的理由与 {@link #filtered} 一样：界面每帧都要问一次。
     */
    private List<BookRef> base(List<BookRef> all) {
        int revision = ShelfStore.revision();
        if (all == baseFrom && tab == baseTab && revision == baseRevision) return baseBooks;

        baseFrom = all;
        baseTab = tab;
        baseRevision = revision;
        baseBooks = tab == Tab.SHELF ? ShelfStore.shelved(all) : all;

        // 底表换了，筛选结果当然作废
        forgetFiltered();
        return baseBooks;
    }

    //  搜索

    private String query() {
        return search == null ? "" : search.getValue();
    }

    /**
     * 当前该显示的那些书。
     *
     * 结果缓存着，只在【查询变了】或【底表换了】时重算。界面每帧都要问一次，
     * 而每帧给几十本书各算一次分、再排一次序，算出来的东西一帧之内就扔掉——
     * 音乐那边的曲库就栽在这种地方（见 BookSources.allBooks 的注释）。
     *
     * 判"底表变没变"用的是引用相等：{@link #base} 在输入不变时一直返回同一个
     * List 实例，所以 == 就够，不必逐本比。
     */
    private List<BookRef> filtered(List<BookRef> base) {
        String q = query();

        // 换了搜索词就回到顶部：接着上一次的滚动位置看一份新结果，很像"搜出来是空的"。
        // 这一句独立于下面的缓存判断——底表变了也会重算，但那时不该动滚动位置
        if (!q.equals(scrollQuery)) {
            scrollQuery = q;
            scrollOffset = 0;
        }

        if (base == filteredFrom && q.equals(filteredQuery)) return filteredBooks;

        filteredFrom = base;
        filteredQuery = q;
        filteredBooks = select(base, q);
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

    /** 书变少时（取消了收藏、卸了个模组）必须夹紧，否则会滚到空白处 */
    private void clampScroll(int total, int availableHeight, Font font) {
        int visible = Math.max(1, availableHeight / rowHeight(font));
        int maxOffset = Math.max(0, total - visible);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
        if (scrollOffset < 0) scrollOffset = 0;
    }
}
