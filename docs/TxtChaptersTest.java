package com.november.mcphone.feature.reader.txt;

import java.util.ArrayList;
import java.util.List;

/**
 * txt 切章的断言测试，用 javac 单独编，不需要 Minecraft。
 *
 * <h2>它守的是什么</h2>
 *
 * 章节标题的识别是一份<b>规则</b>，而规则的两种错法都很难在游戏里发现：
 *
 *   漏认 —— 目录里少了几章，玩家只会觉得"这本书章节怪怪的"，不会想到是正则的事
 *   误认 —— 正文里一句"他翻到第三章"被当成标题，那一段书就从中间被劈开
 *
 * 后者尤其阴：书照样读得下去，只是目录里多出几条莫名其妙的条目。所以那几条否定环视
 * （第二部<b>分</b>、第一节<b>课</b>、正文<b>完</b>）必须一条一条钉住——它们全是被真实
 * 小说坑出来的，删掉任何一条，测试都得红。
 *
 * 另一半守的是<b>切出来的下标</b>：各章首尾相接、覆盖全文。漏一段就是玩家永远读不到的
 * 一截字，而重叠会让同一段出现两次——两者在界面上都只表现为"这本书好像有点不对"。
 */
public class TxtChaptersTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(what + "  期望 " + expected + "，实际 " + actual);
        }
    }

    static void check(boolean cond, String what) {
        checks++;
        if (!cond) failures.add(what);
    }

    public static void main(String[] args) {
        titlesRecognised();
        notTitles();
        splitCoversEverything();
        preambleAndChapters();
        noTitlesGetsChunked();
        hugeChapterGetsSplit();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + checks + " 条断言");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 条：");
            for (String f : failures) System.out.println("  ✗ " + f);
            System.exit(1);
        }
    }

    static void title(String line) {
        checks++;
        if (!TxtChapters.isTitle(line)) failures.add("该认成标题却没认：「" + line + "」");
    }

    static void plain(String line) {
        checks++;
        if (TxtChapters.isTitle(line)) failures.add("不该认成标题却认了：「" + line + "」");
    }

    /** 真实小说里见得到的各种标题写法 */
    static void titlesRecognised() {
        title("第一章");
        title("第一章 风起");
        title("第一章　风起");            // 全角空格，从 Word 里粘出来的常见样子
        title("第1章 风起");
        title("第１章 风起");             // 全角数字
        title("第一百二十三回 大战");
        title("第两百章 转折");           // "两"，中文数字里最容易被规则漏掉的一个
        title("第壹章 起");               // 大写数字
        title("  第十章 山雨");           // 行首缩进
        title("第三卷 少年游");
        title("第七话 约定");
        title("第二集 remake");
        title("序章");
        title("楔子");
        title("引子");
        title("前言");
        title("番外 那年夏天");
        title("后记");
        title("尾声");
        title("终章 再见");
        title("正文 第一部");
        title("序幕");
        title("楔子·初见");
        title("Chapter 1");
        title("CHAPTER XII");
        title("chapter 42 The End");
    }

    /** 正文里长得像标题的句子 —— 这几条是这份规则真正的价值所在 */
    static void notTitles() {
        plain("他忽然想起第三章里说过的那句话，愣了一下。");
        plain("第二部分");                 // 部(?![分赛游])
        plain("第三部赛事即将开始");
        plain("第一节课的铃声响了");         // 节(?!课)
        plain("正文完");                   // 正文(?!完|结)
        plain("正文结");
        plain("第一篇章");                 // 篇(?!章)
        plain("第三集合");                 // 集(?![合和])
        plain("第二天早上，他醒得很早。");    // "天"不在章节量词里
        plain("");
        plain("   ");
        plain("第一章 这一行长得离谱，副题写了整整一段话，这种行在书里是正文不是标题，"
                + "规则那 30 个字的上限就是为了挡住它。");
        plain("正文一。");                 // 光凭词成立的那几个，后面必须隔一个分隔符
        plain("序幕拉开的时候，他还站在原地。");
        plain("这是一段正常的正文，讲的是主角走进了一间屋子，屋子里空无一人。");
    }

    /** 切出来的各章必须首尾相接、覆盖全文，一个字都不能漏 */
    static void splitCoversEverything() {
        String text = String.join("\n",
                "书名：测试",
                "第一章 起",
                "正文一。",
                "第二章 承",
                "正文二。",
                "第三章 转",
                "正文三。") + "\n";

        List<TxtChapters.Chapter> chapters = TxtChapters.split(text);

        eq(chapters.size(), 4, "开头那一段 + 三章");
        eq(chapters.get(0).start(), 0, "第一段从头开始");
        eq(chapters.get(chapters.size() - 1).end(), text.length(), "最后一段到结尾");

        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < chapters.size(); i++) {
            TxtChapters.Chapter c = chapters.get(i);
            if (i > 0) eq(c.start(), chapters.get(i - 1).end(), "第 " + i + " 段与上一段首尾相接");
            check(c.length() > 0, "没有空段");
            joined.append(c.textIn(text));
        }
        eq(joined.toString(), text, "拼回去就是原文，一个字不多不少");
    }

    /** 第一个标题之前那一段没有标题，之后每一段的标题就是那一行 */
    static void preambleAndChapters() {
        String text = "书名：测试\n作者：某某\n第一章 起\n正文一。\n第二章 承\n正文二。\n";
        List<TxtChapters.Chapter> chapters = TxtChapters.split(text);

        eq(chapters.get(0).title(), null, "开头那一段没有标题，由界面起名");
        eq(chapters.get(1).title(), "第一章 起", "标题就是那一行去掉首尾空白");
        eq(chapters.get(2).title(), "第二章 承", "第二章");

        check(chapters.get(1).textIn(text).startsWith("第一章 起"),
                "一章从它的标题行开始，标题也算这一章的内容——读起来才有个开头");
        check(chapters.get(1).textIn(text).contains("正文一。"), "正文跟在标题后面");
        check(!chapters.get(1).textIn(text).contains("第二章"), "下一章的标题不属于这一章");
    }

    /** 认不出章节的书按字数切段，段与段之间在换行处断开 */
    static void noTitlesGetsChunked() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("这是第").append(i).append("段没有任何章节标题的正文，只是普通的句子。\n");
        }
        String text = sb.toString();
        check(text.length() > TxtChapters.CHUNK * 2, "样本要长到足够切成好几段");

        List<TxtChapters.Chapter> chapters = TxtChapters.split(text);

        check(chapters.size() >= 2, "长的无标题正文要切开，实际 " + chapters.size() + " 段");
        for (TxtChapters.Chapter c : chapters) {
            eq(c.title(), null, "无标题的书，每一段都没有标题");
            check(c.length() <= TxtChapters.CHUNK + 400,
                    "每段不超过 CHUNK 再加一点点（往后找段落边界的余量），实际 " + c.length());
        }

        // 断点落在换行之后，段首不该是半句话
        for (int i = 1; i < chapters.size(); i++) {
            char first = text.charAt(chapters.get(i).start());
            check(first == '这', "每段都从一句话的开头起，实际是「" + first + "」");
        }
    }

    /** 只有卷首一个标题、正文几十万字的书，也要切得动 */
    static void hugeChapterGetsSplit() {
        StringBuilder sb = new StringBuilder("第一卷 少年游\n");
        while (sb.length() < TxtChapters.MAX_CHAPTER * 2 + 100) {
            sb.append("他往前走了一步，风从山那边吹过来。\n");
        }
        String text = sb.toString();

        List<TxtChapters.Chapter> chapters = TxtChapters.split(text);

        check(chapters.size() >= 3, "两倍上限的一章要切成至少三段，实际 " + chapters.size());
        eq(chapters.get(0).title(), "第一卷 少年游", "第一段保留原标题");
        eq(chapters.get(1).title(), "第一卷 少年游(2)", "续上的段落缀 (2)");
        eq(chapters.get(2).title(), "第一卷 少年游(3)", "再续缀 (3)");

        for (TxtChapters.Chapter c : chapters) {
            check(c.length() <= TxtChapters.MAX_CHAPTER + 400,
                    "切完之后每段都在上限之内，实际 " + c.length());
        }
    }
}
