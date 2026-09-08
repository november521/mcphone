package com.november.mcphone.feature.reader.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * 翻一本本地 txt —— 手机里自己画的阅读界面。
 *
 * <h2>为什么是"章 + 页"而不是一条长长的滚动</h2>
 *
 * 滚动看着更简单，但对一部几百万字的书行不通：滚动条要知道全文有多长，也就要先对整本书
 * 排版（见 {@link TxtBook} 的类注释，那是好几秒的事）。分成章之后，每次只排一章、
 * 一屏一页，翻页就是换一个起始行——多长的书都是这个代价。
 *
 * 分页还顺带解决了"读到哪儿了"：一对 (第几章, 第几页) 就是进度，存起来只有两个整数。
 *
 * <h2>翻页的三条路</h2>
 *
 * 点屏幕左右两半、滚轮、方向键，三条都通。手机上最顺手的是点：右半边往后、左半边往前，
 * 与所有阅读软件一致。而滚轮与方向键是给"手已经在键盘上"的人留的——这块屏幕不小，
 * 但每次翻页都要把光标移到某个角上点一下，读一晚上就受不了了。
 *
 * <h2>目录盖在正文上，不另开一页</h2>
 *
 * 目录是"看一眼就走"的东西：找到那一章、点进去、接着读。做成另一个 Mode 的话，返回键
 * 的层级就多了一层（正文 → 目录 → 书架），而玩家心里只有"我在看这本书"。所以它是一层
 * 覆盖：开着时正文还在下面，返回键先收它。
 */
public final class TxtReaderPage {

    private static final int PAD = 4;

    /** 顶栏与底栏的高度：一行字加上下各一点余量 */
    private static final int BAR_H = 11;

    /** 行距。正文每行比字高一点点，读起来不挤 */
    private static final int LINE_GAP = 1;

    /** 「目录」那个键的命中区往外放宽多少。手机是缩放显示的，正好按字形边界算会点不中 */
    private static final int HIT_PAD = 2;

    /** 深色字预设（配浅色壁纸的那几个）下垫在正文底下的那一层。与 COLOR_SCRIM 同浓度，反过来压 */
    private static final int SCRIM_LIGHT = 0x66FFFFFF;

    /** 正在读的那本，null 表示没打开或者打不开 */
    private TxtBook book;

    /** 这本书的文件名。book 为 null（读失败）时也要记着，进度与提示都靠它 */
    private String fileName;

    private int chapter;
    private int page;

    /** 目录开着没有 */
    private boolean toc;

    /** 目录的滚动位置（第一行是第几章） */
    private int tocScroll;

    /** 上一帧的几何，点击判定要用同一套数字 —— 与书架页同一个理由 */
    private int textTop, textBottom, textX, textW, lineH = 9;
    private int tocRowH = 11;

    /**
     * 「目录」键这一帧的<b>命中区</b>（已经含了放宽的那一圈）。
     *
     * 画高亮与判点击必须是同一个矩形：两处各算一遍就会出现"看着亮了却点不中"，
     * 而这块屏幕是缩放显示的，差两个像素在 60% 下就是小半个字。
     */
    private int tocHitX, tocHitY, tocHitW, tocHitH;

    /** 「目录」两个字画在哪儿（命中区里居中的那一小块） */
    private int tocTextX, tocTextY;

    /**
     * 打开一本书。
     *
     * 读盘、解码、切章都在这一下里做完（见 {@link TxtLibrary#load}）。读不出来时
     * {@code book} 留成 null，界面会画一句"这本打不开"，而不是白屏。
     */
    public void open(TxtLibrary.Entry entry) {
        close();

        if (entry == null) return;

        fileName = entry.fileName();
        book = TxtLibrary.load(entry);
        toc = false;
        tocScroll = 0;

        TxtProgress.Spot spot = TxtProgress.get(fileName);
        chapter = book == null ? 0 : Math.min(spot.chapter(), Math.max(0, book.chapterCount() - 1));
        page = Math.max(0, spot.page());
    }

    /**
     * 关掉。
     *
     * 一定要把 {@code book} 放掉：那里面攥着整本书的文本，几 MB 的字符串留在一个静态
     * 页面对象上，玩家看完一本又一本，内存就一直涨。手机关机、翻去别的页都会走到这儿。
     */
    public void close() {
        saveProgress();
        book = null;
        fileName = null;
        chapter = 0;
        page = 0;
        toc = false;
        tocScroll = 0;
    }

    /** 返回键：目录开着就先收目录。返回 false 表示"这一层没什么可退的"，由外面退回书架 */
    public boolean back() {
        if (!toc) return false;
        toc = false;
        return true;
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;
        final int top = phoneTop + statusH + 2;
        final int bottom = phoneTop + screenH - navH - 2;

        lineH = font.lineHeight + LINE_GAP;
        textX = x;
        textW = w;

        renderHeader(g, font, x, top, w, mouseX, mouseY);

        textTop = top + BAR_H + 2;
        textBottom = bottom - BAR_H - 2;

        if (book == null) {
            renderBroken(g, font, x, textTop, w);
        } else {
            renderPage(g, font);
        }

        renderFooter(g, font, x, bottom - BAR_H, w);

        // 目录最后画，盖在正文上
        if (toc && book != null) renderToc(g, font, x, textTop, w, textBottom, mouseX, mouseY);
    }

    /** 顶栏：书名 + 右边的「目录」 */
    private void renderHeader(GuiGraphics g, Font font, int x, int y, int w,
                              int mouseX, int mouseY) {

        String label = Component.translatable(toc
                ? "mcphone.reader.txt.close_toc"
                : "mcphone.reader.txt.toc").getString();

        int labelW = font.width(label);
        tocTextX = x + w - labelW;
        tocTextY = y + (BAR_H - font.lineHeight) / 2;

        tocHitX = tocTextX - HIT_PAD;
        tocHitY = y;
        tocHitW = labelW + HIT_PAD * 2;
        tocHitH = BAR_H;

        boolean hovered = GuiUtil.hit(mouseX, mouseY, tocHitX, tocHitY, tocHitW, tocHitH);

        String title = book == null && fileName != null ? fileName
                : (book == null ? "" : book.entry().title());
        g.drawString(font, GuiUtil.truncate(font, title, w - labelW - 6),
                x, tocTextY, FontPalette.title(), false);

        g.drawString(font, label, tocTextX, tocTextY,
                hovered || toc ? FontPalette.link() : FontPalette.dim(), false);

        g.fill(x, y + BAR_H, x + w, y + BAR_H + 1, PhoneTheme.COLOR_DIVIDER);
    }

    /**
     * 正文这一页。
     *
     * 先在正文区垫一层<b>压色</b>：别的页面上一屏只有几行字，直接画在壁纸上看得清；
     * 而这一页是满屏的字，还要一读半小时——底下是一张花壁纸的话，读十分钟眼睛就散了。
     *
     * 压的方向跟着字色预设走：浅色字压暗、深色字（配浅色壁纸的那几个预设）压亮。
     * 一律压暗的话，选了黑字的玩家会得到"黑底黑字"。
     */
    private void renderPage(GuiGraphics g, Font font) {
        List<FormattedCharSequence> lines = book.lines(chapter, font, textW);
        int perPage = linesPerPage();

        clampPage(lines.size(), perPage);

        g.fill(textX - PAD, textTop - 1, textX + textW + PAD, textBottom,
                FontPalette.current().darkText() ? SCRIM_LIGHT : PhoneTheme.COLOR_SCRIM);

        int y = textTop;
        int from = page * perPage;
        for (int i = from; i < Math.min(lines.size(), from + perPage); i++) {
            g.drawString(font, lines.get(i), textX, y, FontPalette.body(), false);
            y += lineH;
        }
    }

    /** 读不出来的时候说清楚是哪一本、去哪儿看原因 */
    private void renderBroken(GuiGraphics g, Font font, int x, int y, int w) {
        g.drawString(font, Component.translatable("mcphone.reader.txt.broken").getString(),
                x, y, FontPalette.title(), false);
        y += font.lineHeight + 2;

        for (var line : font.split(Component.translatable("mcphone.reader.txt.broken_hint"), w)) {
            g.drawString(font, line, x, y, FontPalette.dim(), false);
            y += font.lineHeight;
        }
    }

    /**
     * 底栏：左边是这一章叫什么，右边是"第几页 / 百分之几"。
     *
     * 百分比按全书字数算（见 {@link TxtBook#percentAt}）——玩家问"看了多少了"，
     * 心里想的是这个数，不是章号。
     */
    private void renderFooter(GuiGraphics g, Font font, int x, int y, int w) {
        g.fill(x, y - 1, x + w, y, PhoneTheme.COLOR_DIVIDER);

        int textY = y + (BAR_H - font.lineHeight) / 2 + 1;
        if (book == null) return;

        String right = (page + 1) + "/" + pageCount(font) + "  " + book.percentAt(chapter) + "%";
        int rightW = font.width(right);
        g.drawString(font, right, x + w - rightW, textY, FontPalette.dim(), false);

        String left = chapterName(chapter);
        g.drawString(font, GuiUtil.truncate(font, left, w - rightW - 6),
                x, textY, FontPalette.subtle(), false);
    }

    /**
     * 目录。
     *
     * 铺一层不透明的底再画：半透明的话正文会从字缝里透出来，一页目录看着像双重曝光。
     */
    private void renderToc(GuiGraphics g, Font font, int x, int y, int w, int bottom,
                           int mouseX, int mouseY) {

        g.fill(x - PAD, y, x + w + PAD, bottom, PhoneTheme.COLOR_TOAST_BG);

        tocRowH = font.lineHeight + 2;
        int rows = Math.max(1, (bottom - y) / tocRowH);
        clampTocScroll(rows);

        int rowY = y;
        for (int i = tocScroll; i < Math.min(book.chapterCount(), tocScroll + rows); i++) {
            boolean hovered = GuiUtil.hit(mouseX, mouseY, x, rowY, w, tocRowH);
            if (hovered) g.fill(x, rowY, x + w, rowY + tocRowH, PhoneTheme.COLOR_ROW_HOVER);

            int color = i == chapter ? FontPalette.link()
                    : (hovered ? FontPalette.title() : FontPalette.body());
            g.drawString(font, GuiUtil.truncate(font, chapterName(i), w),
                    x, rowY + 1, color, false);
            rowY += tocRowH;
        }
    }

    /** 这一章叫什么。没有标题的（认不出章节的书）由这里起名，那是界面的事，不是切章的事 */
    private String chapterName(int index) {
        String title = book == null ? null : book.chapterTitle(index);
        if (title != null && !title.isBlank()) return title;
        return Component.translatable("mcphone.reader.txt.section", index + 1).getString();
    }

    //  输入

    /**
     * 点一下。
     *
     * 顺序要紧：先「目录」键、再目录里的行、最后才是翻页——目录开着时点在正文区上，
     * 玩家的意思是"点这一章"，不是"翻页"。
     */
    public boolean mouseClicked(double mx, double my, Font font) {
        if (GuiUtil.hit(mx, my, tocHitX, tocHitY, tocHitW, tocHitH)) {
            toc = !toc;
            if (toc) tocScroll = Math.max(0, chapter - 1);   // 打开就停在正在读的这一章上
            return true;
        }

        if (book == null) return false;

        if (toc) {
            if (my < textTop || my >= textBottom) return true;

            int index = tocScroll + (int) ((my - textTop) / Math.max(1, tocRowH));
            if (index >= 0 && index < book.chapterCount()) {
                goToChapter(index);
                toc = false;
            }
            return true;
        }

        if (my < textTop || my >= textBottom) return false;

        // 左半边往前、右半边往后。所有阅读软件都是这个，不必再教一遍
        if (mx < textX + textW / 2.0) previousPage(font);
        else nextPage(font);
        return true;
    }

    public boolean mouseScrolled(double scrollY, Font font) {
        if (book == null) return false;

        if (toc) {
            tocScroll = Math.max(0, tocScroll + (scrollY > 0 ? -1 : 1));
            return true;
        }

        if (scrollY > 0) previousPage(font);
        else nextPage(font);
        return true;
    }

    /** 方向键 / 翻页键 / 空格。ESC 不在这儿：那是全局的关机键 */
    public boolean keyPressed(int keyCode, Font font) {
        if (book == null) return false;

        return switch (keyCode) {
            case 263, 266 -> { previousPage(font); yield true; }   // ← / PageUp
            case 262, 267, 32 -> { nextPage(font); yield true; }   // → / PageDown / 空格
            default -> false;
        };
    }

    //  翻页

    /**
     * 往后一页；这一章翻完了就进下一章。
     *
     * 全书最后一页再往后什么都不做——弹一句"读完了"更像是在打断人，而页码停着不动
     * 本身就说明到头了。
     */
    private void nextPage(Font font) {
        int pages = pageCount(font);
        if (page + 1 < pages) {
            page++;
        } else if (chapter + 1 < book.chapterCount()) {
            chapter++;
            page = 0;
        } else {
            return;
        }
        saveProgress();
    }

    /** 往前一页；到本章开头就退回上一章的<b>最后一页</b>，而不是它的第一页 */
    private void previousPage(Font font) {
        if (page > 0) {
            page--;
        } else if (chapter > 0) {
            chapter--;
            page = Math.max(0, pageCount(font) - 1);
        } else {
            return;
        }
        saveProgress();
    }

    private void goToChapter(int index) {
        chapter = Math.max(0, Math.min(book.chapterCount() - 1, index));
        page = 0;
        saveProgress();
    }

    private void saveProgress() {
        if (book != null && fileName != null) TxtProgress.set(fileName, chapter, page);
    }

    //  排版用的几个数

    private int linesPerPage() {
        return Math.max(1, (textBottom - textTop) / Math.max(1, lineH));
    }

    private int pageCount(Font font) {
        return book == null ? 1 : book.pageCount(chapter, font, textW, linesPerPage());
    }

    /**
     * 页码夹回合法范围。
     *
     * 这一步不能省：页数是随屏幕宽度变的，而进度里存的是上一次的页码——换了界面大小、
     * 从手机换成平板之后，存着的第 7 页在新的排版里可能根本不存在。
     */
    private void clampPage(int lineCount, int perPage) {
        int pages = Math.max(1, (lineCount + perPage - 1) / perPage);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
    }

    private void clampTocScroll(int rows) {
        int max = Math.max(0, book.chapterCount() - rows);
        if (tocScroll > max) tocScroll = max;
        if (tocScroll < 0) tocScroll = 0;
    }
}
