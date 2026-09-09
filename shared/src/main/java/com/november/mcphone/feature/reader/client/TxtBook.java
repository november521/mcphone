package com.november.mcphone.feature.reader.client;

import com.november.mcphone.feature.reader.txt.TextDecoder;
import com.november.mcphone.feature.reader.txt.TxtChapters;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 读进内存的一本 txt —— 全文、切好的章，以及"当前这一章排成了哪些行"。
 *
 * <h2>排版只对当前这一章做</h2>
 *
 * 折行（{@link Font#split}）的代价与字数成正比。对整本书折一次要几秒钟，还要把上百万行
 * 一直攥着；而玩家一次只看得见二十行。所以这里只缓存<b>一章</b>的折行结果，翻到下一章
 * 时重算——一章两三千字，一帧之内就完了。
 *
 * 缓存的凭据是"哪一章 + 多宽"：换章要重排，<b>屏幕宽度变了也要重排</b>（改界面大小、
 * 从手机换到平板、副手 HUD 与全屏的宽度不同）。少判一个宽度，就会出现"字都挤在左半边"
 * 或者"每行末尾被切掉"。
 *
 * <h2>空行折叠</h2>
 *
 * 网上流传的 txt 有一半是"每段之间空一行"，也有的空三四行。原样排的话，这块一屏只有
 * 二十行的屏幕会有一半在显示空白。所以连着的空行一律并成一行——保留段落之间的呼吸，
 * 又不让排版被源文件的随意格式带走。
 */
public final class TxtBook {

    /** 全文。切章存的是下标，正文都从这儿取 */
    private final String text;

    private final List<TxtChapters.Chapter> chapters;

    private final TxtLibrary.Entry entry;

    private final Charset charset;

    /** 上一次排版的结果与它的凭据，见类注释 */
    private int laidOutChapter = -1;
    private int laidOutWidth = -1;
    private List<FormattedCharSequence> laidOutLines = List.of();

    private TxtBook(TxtLibrary.Entry entry, String text, Charset charset,
                    List<TxtChapters.Chapter> chapters) {
        this.entry = entry;
        this.text = text;
        this.charset = charset;
        this.chapters = chapters;
    }

    /** 解码 + 切章。这是唯一的入口，由 {@link TxtLibrary#load} 调 */
    static TxtBook of(TxtLibrary.Entry entry, byte[] bytes) {
        Charset charset = TextDecoder.detect(bytes);
        String text = TextDecoder.decode(bytes);
        return new TxtBook(entry, text, charset, TxtChapters.split(text));
    }

    public TxtLibrary.Entry entry() {
        return entry;
    }

    /** 猜出来的编码。显示在书里那一行"信息"上，乱码时玩家能据此去改文件 */
    public Charset charset() {
        return charset;
    }

    public int chapterCount() {
        return chapters.size();
    }

    public boolean isEmpty() {
        return chapters.isEmpty();
    }

    /** 第几章的标题；这一段没有标题（认不出章节的书）时返回 null，由界面起名 */
    public String chapterTitle(int index) {
        TxtChapters.Chapter chapter = chapterAt(index);
        return chapter == null ? null : chapter.title();
    }

    /**
     * 读到这一章时，整本书读了百分之几。
     *
     * 按<b>字数</b>算而不是按章数：各章长短差得远，按章数算会出现"翻了三章才走 1%"
     * 与"一章就跳 20%"。这个数只用来给玩家一个大概，不必精确到小数点后两位。
     */
    public int percentAt(int chapterIndex) {
        if (text.isEmpty()) return 0;

        TxtChapters.Chapter chapter = chapterAt(chapterIndex);
        if (chapter == null) return 0;
        return (int) Math.round(100.0 * chapter.start() / text.length());
    }

    private TxtChapters.Chapter chapterAt(int index) {
        if (index < 0 || index >= chapters.size()) return null;
        return chapters.get(index);
    }

    /**
     * 这一章折行之后是哪些行。
     *
     * 结果缓存着，界面每帧问都行——翻页只是换一个起始行，不必重排。
     */
    public List<FormattedCharSequence> lines(int chapterIndex, Font font, int width) {
        if (width <= 0) return List.of();

        if (chapterIndex == laidOutChapter && width == laidOutWidth) return laidOutLines;

        TxtChapters.Chapter chapter = chapterAt(chapterIndex);
        if (chapter == null) return List.of();

        laidOutChapter = chapterIndex;
        laidOutWidth = width;
        laidOutLines = layout(chapter.textIn(text), font, width);
        return laidOutLines;
    }

    /** 一章排出来共几页 */
    public int pageCount(int chapterIndex, Font font, int width, int linesPerPage) {
        if (linesPerPage <= 0) return 1;

        int lines = lines(chapterIndex, font, width).size();
        return Math.max(1, (lines + linesPerPage - 1) / linesPerPage);
    }

    /**
     * 逐段折行。
     *
     * 一段一段地送进 {@link Font#split} 而不是整章一次：整章里的换行会被当成普通空白，
     * 段落就全连在一起了。
     */
    private static List<FormattedCharSequence> layout(String chapterText, Font font, int width) {
        List<FormattedCharSequence> out = new ArrayList<>();

        boolean lastBlank = false;
        for (String paragraph : chapterText.split("\n", -1)) {
            String line = stripTrailing(paragraph);

            if (line.isBlank()) {
                // 连着的空行并成一行，理由见类注释。开头的空行直接丢掉
                if (!lastBlank && !out.isEmpty()) out.add(FormattedCharSequence.EMPTY);
                lastBlank = true;
                continue;
            }

            lastBlank = false;
            out.addAll(font.split(FormattedText.of(line), width));
        }
        return List.copyOf(out);
    }

    /** 去掉行尾空白：源文件里常有一串全角空格，留着会让折行提前一行 */
    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
        return end == line.length() ? line : line.substring(0, end);
    }
}
