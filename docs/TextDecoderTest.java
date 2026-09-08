package com.november.mcphone.feature.reader.txt;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * txt 编码识别的断言测试，用 javac 单独编，不需要 Minecraft。
 *
 * <h2>它守的是什么</h2>
 *
 * 猜错编码不会报错，只会<b>满屏乱码</b>——而乱码在游戏里是"这本书打不开"，玩家不会去
 * 想是编码的事，只会觉得这个功能坏了。更麻烦的是它<b>只对某些文件发生</b>：开发者手边
 * 的样本多半是 UTF-8，一路都正常，而玩家从网上下的那本是 2009 年存的 GBK。
 *
 * 所以这里的样本是<b>拿真编码器现造</b>的：把同一句中文分别用 GBK、Big5、UTF-8 编出来，
 * 再要求解回原句。这比写死一串字节可靠——写死的字节看不出错在哪，而这样错了会直接
 * 打印出解成了什么。
 *
 * 另一条守的是 BOM：记事本另存的 UTF-8 会在开头塞三个字节，不吃掉它的话，正文第一个
 * 字前面会多一个看不见的字符，而它在排版时占宽度——症状是"第一行莫名其妙缩进了一点"。
 */
public class TextDecoderTest {

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

    /** 一段像小说的简体中文，长度够让打分拉开差距 */
    static final String SIMPLIFIED =
            "第一章 风起\n"
            + "他往前走了一步，风从山那边吹过来，带着一点凉意。少年抬起头，看见远处的山脊上\n"
            + "有一线光。那是他等了整整三年的东西。\n"
            + "「走吧。」他说。\n";

    /** 同一段的繁体，用来验 Big5 */
    static final String TRADITIONAL =
            "第一章 風起\n"
            + "他往前走了一步，風從山那邊吹過來，帶著一點涼意。少年抬起頭，看見遠處的山脊上\n"
            + "有一線光。那是他等了整整三年的東西。\n"
            + "「走吧。」他說。\n";

    public static void main(String[] args) {
        emptyAndAscii();
        utf8();
        bom();
        gbk();
        big5();
        newlines();
        utf8ValidityIsNotFooledByTruncation();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + checks + " 条断言");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 条：");
            for (String f : failures) System.out.println("  ✗ " + f);
            System.exit(1);
        }
    }

    /** 取一个编码，取不到就返回 null（裁过的运行时可能没有），调用方自己跳过 */
    static Charset charset(String name) {
        return Charset.isSupported(name) ? Charset.forName(name) : null;
    }

    static void emptyAndAscii() {
        eq(TextDecoder.decode(null), "", "null 不炸");
        eq(TextDecoder.decode(new byte[0]), "", "空文件就是空字符串");

        String ascii = "Chapter 1\nHe took a step forward.\n";
        eq(TextDecoder.decode(ascii.getBytes(StandardCharsets.US_ASCII)), ascii, "纯英文原样解出");
        eq(TextDecoder.detect(ascii.getBytes(StandardCharsets.US_ASCII)), StandardCharsets.UTF_8,
                "纯 ASCII 当 UTF-8（两者在这一段上是同一件事）");
    }

    static void utf8() {
        byte[] bytes = SIMPLIFIED.getBytes(StandardCharsets.UTF_8);

        eq(TextDecoder.detect(bytes), StandardCharsets.UTF_8, "UTF-8 的中文认成 UTF-8");
        eq(TextDecoder.decode(bytes), SIMPLIFIED, "解回原文，一个字不差");
    }

    static void bom() {
        byte[] body = SIMPLIFIED.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF; withBom[1] = (byte) 0xBB; withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        eq(TextDecoder.decode(withBom), SIMPLIFIED, "UTF-8 的 BOM 要吃掉，不能留成正文第一个字");
        check(!TextDecoder.decode(withBom).startsWith("﻿"), "正文开头不许有 BOM 字符");

        // UTF-16：Windows 记事本的"Unicode"另存就是这个
        byte[] le = ("﻿" + SIMPLIFIED).getBytes(StandardCharsets.UTF_16LE);
        eq(TextDecoder.decode(le), SIMPLIFIED, "UTF-16 小端连 BOM 一起认出来");

        byte[] be = ("﻿" + SIMPLIFIED).getBytes(StandardCharsets.UTF_16BE);
        eq(TextDecoder.decode(be), SIMPLIFIED, "UTF-16 大端");
    }

    /** 老 txt 的大头：GBK / GB2312，都由 GB18030 一并解对 */
    static void gbk() {
        Charset gbk = charset("GBK");
        if (gbk == null) { check(true, "这个运行时没有 GBK，跳过"); return; }

        byte[] bytes = SIMPLIFIED.getBytes(gbk);

        check(!TextDecoder.isValidUtf8(bytes, bytes.length),
                "GBK 的中文不该通过 UTF-8 的合法性检查——这一条是整个判断的地基");
        eq(TextDecoder.detect(bytes).name(), "GB18030", "GBK 的文件认成 GB18030（它是 GBK 的超集）");
        eq(TextDecoder.decode(bytes), SIMPLIFIED, "解回原文，一个字不差");

        Charset big5 = charset("Big5");
        if (big5 != null) {
            check(TextDecoder.score(bytes, bytes.length, charset("GB18030"))
                            > TextDecoder.score(bytes, bytes.length, big5),
                    "简体样本上 GB18030 的分必须高过 Big5");
        }
    }

    /** 港台来源的繁体 txt */
    static void big5() {
        Charset big5 = charset("Big5");
        if (big5 == null) { check(true, "这个运行时没有 Big5，跳过"); return; }

        byte[] bytes = TRADITIONAL.getBytes(big5);

        eq(TextDecoder.detect(bytes).name(), "Big5", "繁体 Big5 的文件不能被当成 GB18030");
        eq(TextDecoder.decode(bytes), TRADITIONAL, "解回原文");

        Charset gb = charset("GB18030");
        if (gb != null) {
            int gbScore = TextDecoder.score(bytes, bytes.length, gb);
            int big5Score = TextDecoder.score(bytes, bytes.length, big5);
            check(big5Score > gbScore,
                    "繁体样本上 Big5 的分要高过 GB18030（" + big5Score + " vs " + gbScore + "）");
            check(gbScore < 0, "用错编码解出来的是一堆生僻字，分数应该是负的，实际 " + gbScore);
        }
    }

    /** 换行统一成 \n —— 留着 \r 会在每行末尾多出一个有宽度的字符 */
    static void newlines() {
        String crlf = "第一章\r\n正文一。\r\n";
        eq(TextDecoder.decode(crlf.getBytes(StandardCharsets.UTF_8)), "第一章\n正文一。\n",
                "Windows 换行换成 \\n");

        String cr = "第一章\r正文一。\r";
        eq(TextDecoder.decode(cr.getBytes(StandardCharsets.UTF_8)), "第一章\n正文一。\n",
                "老 Mac 的单个 \\r 也算换行");
    }

    /**
     * 只看开头一段时，末尾那个被切成两半的多字节字符不算"非法 UTF-8"。
     *
     * 不处理这一条的话，一本 UTF-8 的书会因为第 65536 个字节恰好落在一个汉字中间而被
     * 判成不合法，转去按 GBK 解——整本书乱码，而且只在特定长度的文件上发生。
     */
    static void utf8ValidityIsNotFooledByTruncation() {
        byte[] bytes = "啊啊啊啊啊".getBytes(StandardCharsets.UTF_8);   // 每个字三字节

        check(TextDecoder.isValidUtf8(bytes, bytes.length), "完整的当然合法");
        check(TextDecoder.isValidUtf8(bytes, bytes.length - 1), "末尾切掉一个字节，仍算合法");
        check(TextDecoder.isValidUtf8(bytes, bytes.length - 2), "切掉两个字节");
        check(TextDecoder.isValidUtf8(bytes, 4), "从一个字中间截断");
        check(TextDecoder.isValidUtf8(bytes, 0), "长度 0 不炸");

        // 真正非法的序列仍然要判出来：0xC3 后面必须跟一个续字节
        byte[] broken = {(byte) 0xC3, (byte) 0x28, 'a', 'b', 'c', 'd', 'e'};
        check(!TextDecoder.isValidUtf8(broken, broken.length), "真非法的序列要认出来");
    }
}
