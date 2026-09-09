package com.november.mcphone.feature.reader.txt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把一部 txt 切成章 —— 纯算术，<b>不 import 任何 Minecraft 类型</b>。
 *
 * <h2>为什么要切</h2>
 *
 * 一部小说三五 MB、上百万字。不切的话，翻页就得对整本书排版，而排版（按屏幕宽度折行）
 * 是按字数走的——点开一本书要卡上好几秒，还得把上百万行的折行结果一直攥在内存里。
 * 切成章之后，每次只对<b>当前这一章</b>排版，两三千字，一帧之内就算完了。
 *
 * 章也是玩家要的东西：目录、"我看到第几章了"，都从这一步来。
 *
 * <h2>怎么认章节标题</h2>
 *
 * 认的是<b>整行</b>，不是"文中出现了第几章"：正文里写"他想起第三章说过的话"是常事，
 * 而那一行不会只有这几个字。所以规则是：一行去掉首尾空白之后，整行必须像个标题，
 * 且不超过 30 个字。
 *
 * 具体的模式抄的是 <a href="https://github.com/gedoor/legado">legado（阅读）</a>
 * 自带的 TXT 目录规则（{@code app/src/main/assets/defaultData/txtTocRule.json}）里
 * 最通用的那一条，它是这类软件里被真实小说验证得最多的一份：
 *
 * <pre>
 *   第[数字][章节回卷集部篇话]  —— 数字认阿拉伯数字与中文数字（含"两"与大写壹贰叁）
 *   序章 楔子 引子 前言 正文 终章 后记 尾声 番外
 *   Chapter 12 / CHAPTER XII    —— 英文小说
 * </pre>
 *
 * 那几个<b>否定环视</b>是这份规则的精华，全是被真实文本坑出来的：
 *
 * <pre>
 *   正文(?!完|结)   "正文完"是全书结尾的落款，不是新的一章
 *   节(?!课)        "第一节课"是正文里的句子
 *   集(?![合和])    "第三集合" "第三集和"
 *   部(?![分赛游])  "第二部分" —— 这个最常见，几乎每本书都会撞上
 *   篇(?!章)        "第一篇章"
 * </pre>
 *
 * <h2>认不出来怎么办</h2>
 *
 * 不是所有 txt 都有规整的章节标题——扫描版、贴吧复制来的、外文原文都可能没有。那时候
 * 按字数切成一段一段（{@link #CHUNK}），标题给 {@code null}，由界面叫它"第几段"。
 * 切总比不切强：一段两千字仍然翻得动，而不切就是前面说的那个卡几秒。
 *
 * <h2>太长的章也要再切</h2>
 *
 * 有的书只在卷首放一个"第一卷"，一卷几十万字。它形式上是一章，但对排版来说与"不切"
 * 没区别。所以切完还要过一遍：超过 {@link #MAX_CHAPTER} 的章按段落再切开，续上的那几段
 * 在标题后面缀 {@code (2)}、{@code (3)}——那是数字与括号，任何语言下都读得懂。
 */
public final class TxtChapters {

    private TxtChapters() {}

    /**
     * 一章。{@code start}/{@code end} 是在全文里的字符下标（左闭右开）。
     *
     * 存下标而不是存那段字：一本书切成几百章，每章都拷一份字符串等于把整本书再存一遍。
     *
     * @param title 章节名；{@code null} 表示这一段没有标题（认不出章节的书、或正文
     *              开头在第一个标题之前的那一段），由界面给它起个名字
     */
    public record Chapter(String title, int start, int end) {

        public int length() {
            return end - start;
        }

        /** 取出这一章的正文 */
        public String textIn(String whole) {
            return whole.substring(Math.max(0, start), Math.min(whole.length(), end));
        }
    }

    /** 认不出章节时，按这么多字切一段 */
    static final int CHUNK = 2000;

    /** 一章最多这么长，超了就再切开。理由见类注释 */
    static final int MAX_CHAPTER = 20_000;

    /** 切段时最多往后找这么远的段落边界，找不到就当场切。免得整段没有换行时一直找到天边 */
    private static final int BREAK_SEARCH = 400;

    /** 标题行最多这么长。再长的一行是正文，不是标题 */
    private static final int MAX_TITLE_LINE = 40;

    /**
     * 一行是不是章节标题。模式的由来见类注释。
     *
     * 三支合起来只有一个意思："整行就是个标题"——所以行首只允许少量空白，副题也卡死在
     * 30 个字以内。三支的<b>尾巴规则不一样</b>，这是被真实文本逼出来的：
     *
     * <pre>
     *   第 N 章  后面可以直接接副题     "第一章风起" 不带空格的写法很常见
     *   序章 楔子 正文…  后面必须隔一个分隔符  否则 "正文一。" 这样的正文首句会被当成标题
     * </pre>
     *
     * legado 那份规则对两支用的是同一条尾巴（{@code .{0,30}}），于是"正文一。"在它那儿
     * 也算标题。这里收紧一档：光凭词本身成立的那些，后面要么什么都没有，要么隔着一个
     * 分隔符再接副题。
     */
    private static final Pattern TITLE = Pattern.compile(
            "^[ \\t\\u3000]{0,4}(?:"
            // 一、光凭这个词就是标题
            + "(?:序章|序言|序幕|楔子|引子|前言|序|后记|尾声|终章|番外|正文(?!完|结))"
            + "(?:[ \\t\\u3000·、，,：:．.\\-—－_~～]{1,4}.{0,30})?"
            // 二、第 N 章 / 回 / 卷 …
            + "|第\\s{0,4}[0-9０-９〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,12}\\s{0,4}"
            + "(?:章|节(?!课)|回|卷|集(?![合和])|部(?![分赛游])|篇(?!章)|话).{0,30}"
            // 三、英文小说
            + "|[Cc][Hh][Aa][Pp][Tt][Ee][Rr]\\s+[0-9IVXLCivxlc]{1,8}.{0,30}"
            + ")$");

    /** 这一行是不是章节标题 */
    public static boolean isTitle(String line) {
        if (line == null) return false;

        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_TITLE_LINE) return false;

        return TITLE.matcher(trimmed).matches();
    }

    /**
     * 切章。
     *
     * 返回的各章首尾相接、覆盖全文，不会漏字也不会重叠——阅读界面按章拼进度百分比，
     * 漏一段就会出现"读到 101%"。空文本返回空表。
     */
    public static List<Chapter> split(String text) {
        if (text == null || text.isEmpty()) return List.of();

        // 一个标题都没有时 byTitle 给的就是"整本一段"，与按字数切是同一条路——
        // 那一段接着会被 splitLong 按 CHUNK 切开
        List<Chapter> chapters = byTitle(text);
        if (chapters.isEmpty()) chapters = List.of(new Chapter(null, 0, text.length()));

        List<Chapter> out = new ArrayList<>(chapters.size());
        for (Chapter chapter : chapters) splitLong(text, chapter, out);
        return List.copyOf(out);
    }

    /** 按标题行切。第一个标题之前的那一段（书名页、简介）单独成一章，标题为 null */
    private static List<Chapter> byTitle(String text) {
        List<Chapter> out = new ArrayList<>();

        int lineStart = 0;
        int chapterStart = 0;
        String chapterTitle = null;

        // 手写扫行而不是 split("\n")：一部小说几十万行，split 会一次性造出几十万个
        // 字符串对象，而这里要的只是每行的起止下标
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = text.length();

            String line = text.substring(lineStart, lineEnd);
            if (isTitle(line)) {
                // 标题前面那一段收尾。开头的空白段（第一行就是标题）不算一章
                if (lineStart > chapterStart || chapterTitle != null) {
                    out.add(new Chapter(chapterTitle, chapterStart, lineStart));
                }
                chapterTitle = line.strip();
                chapterStart = lineStart;
            }

            if (lineEnd >= text.length()) break;
            lineStart = lineEnd + 1;
        }

        if (chapterStart < text.length() || chapterTitle != null) {
            out.add(new Chapter(chapterTitle, chapterStart, text.length()));
        }
        return out;
    }

    /**
     * 太长的一章按段落再切开，续上的缀 (2)(3)。不长的原样放进去。
     *
     * 有标题的按 {@link #MAX_CHAPTER} 切、没标题的按 {@link #CHUNK} 切：前者是"一章大到
     * 会拖慢排版"才动它，切完还得让玩家认得出这是原来那一章；后者本来就没有章的概念，
     * 切细一点反而好翻——每段两千字，与正常一章差不多。
     */
    private static void splitLong(String text, Chapter chapter, List<Chapter> out) {
        final int limit = chapter.title() == null ? CHUNK : MAX_CHAPTER;
        if (chapter.length() <= limit) {
            if (chapter.length() > 0) out.add(chapter);
            return;
        }

        int part = 1;
        int start = chapter.start();
        while (start < chapter.end()) {
            int target = Math.min(chapter.end(), start + limit);
            int cut = breakAt(text, target, chapter.end());

            String title = chapter.title() == null || part == 1
                    ? chapter.title()
                    : chapter.title() + "(" + part + ")";
            out.add(new Chapter(title, start, cut));

            start = cut;
            part++;
        }
    }

    /**
     * 从 {@code target} 往后找一个段落边界（换行）当切点，找不到就在原地切。
     *
     * 往后找而不是往前：往前找会让上一段越切越短，而往后最多多带一小段。段中间硬切
     * 会把一句话劈成两半，翻页时看着像掉了字。
     */
    private static int breakAt(String text, int target, int limit) {
        if (target >= limit) return limit;

        int end = Math.min(limit, target + BREAK_SEARCH);
        int nl = text.indexOf('\n', target);
        if (nl >= 0 && nl < end) return nl + 1;
        return target;
    }
}
