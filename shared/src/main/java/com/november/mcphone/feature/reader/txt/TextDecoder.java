package com.november.mcphone.feature.reader.txt;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 猜一份 txt 是什么编码，并把它解出来 —— 纯算术，<b>不 import 任何 Minecraft 类型</b>。
 *
 * <h2>为什么非猜不可</h2>
 *
 * 中文小说的 txt 在网上流传了二十年，编码是什么全看当年是谁存的：早年的站点导出的是
 * GBK，手机端存的多半是 UTF-8，港台来源的是 Big5，Windows 记事本另存还会在开头塞三个
 * 字节的 BOM。玩家手里那一份是哪种，他自己通常也不知道——他只知道"用某某阅读器打开是好的"。
 *
 * 猜错的代价不是报错，是<b>满屏乱码</b>：拿 UTF-8 去读 GBK，每个汉字都会变成两个问号。
 * 所以这一步必须自己做，不能简单地 {@code new String(bytes)} 了事（那用的是平台默认编码，
 * 在中文 Windows 上恰好常常是对的，在 Linux 服务器与英文系统上一律是错的）。
 *
 * <h2>三步：BOM → 严格 UTF-8 → 打分</h2>
 *
 * <ol>
 * <li><b>BOM</b>：开头有 UTF-8 / UTF-16 的字节序标记就照它来。这是唯一"确定"的一步，
 *     文件自己声明了自己是什么。</li>
 * <li><b>严格解一遍 UTF-8</b>：UTF-8 的多字节序列有很强的自校验性，一段 GBK 中文
 *     几乎不可能整段通过 UTF-8 的合法性检查。所以"解得下来"就当它是 UTF-8——
 *     这一步几乎不会误判，而现今新存的文件多数是 UTF-8。</li>
 * <li><b>打分</b>：剩下的就是各家双字节编码。同一串字节用 GB18030 与 Big5 各解一遍，
 *     数一数解出来的字里"像正常中文"的有多少（{@link #score}），谁高算谁。简体小说
 *     用 Big5 解会得到一堆生僻字与符号，反之亦然，这个差距很大，不需要更聪明的办法。</li>
 * </ol>
 *
 * 选 GB18030 而不是 GBK：它是 GBK 的超集，能把 GBK 与 GB2312 的文件一并解对，
 * 少一种要区分的情况。
 *
 * <h2>只拿开头一段来猜</h2>
 *
 * 一部小说几 MB，而"是哪种编码"这件事在头几十 KB 里就答完了。全文都拿去试解两遍纯属
 * 白烧 CPU——玩家点开一本书要等的就是这一下。
 */
public final class TextDecoder {

    private TextDecoder() {}

    /** 猜编码时最多看开头这么多字节。几十 KB 足够定性，见类注释 */
    static final int SAMPLE = 64 * 1024;

    /** UTF-8 的 BOM。记事本"另存为 UTF-8"就会写它 */
    private static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /**
     * 把这份文件解成文本 —— 编码自己猜，BOM 自己吃掉。
     *
     * 猜错时不抛也不留空：所有解码器都用"替换"策略，实在解不出来的字节变成 �。
     * 一本书里有几个 � 仍然读得下去，而抛异常等于"这本书打不开"。
     */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";

        Charset charset = detect(bytes);
        int offset = bomLength(bytes, charset);

        String text = new String(bytes, offset, bytes.length - offset, charset);

        // 统一换行：后面按行找章节标题、按行排版，留着 \r 会让每行末尾多一个看不见的字符，
        // 它在 Minecraft 的字体里宽度不为零，行尾会莫名其妙多出一小截
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 这份字节是什么编码。
     *
     * 单独公开是为了让界面能把猜出来的结果显示给玩家看——乱码的时候他至少知道
     * 我们猜的是哪一种，改文件编码时心里有数。
     */
    public static Charset detect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return StandardCharsets.UTF_8;

        // 1) 文件自己声明的最可信
        if (startsWith(bytes, BOM_UTF8)) return StandardCharsets.UTF_8;
        if (bytes.length >= 2) {
            int b0 = bytes[0] & 0xFF, b1 = bytes[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) return StandardCharsets.UTF_16LE;
            if (b0 == 0xFE && b1 == 0xFF) return StandardCharsets.UTF_16BE;
        }

        int length = Math.min(bytes.length, SAMPLE);

        // 2) 严格解一遍 UTF-8：多字节序列自校验，蒙不过去
        if (isValidUtf8(bytes, length)) return StandardCharsets.UTF_8;

        // 3) 双字节的几家各解一遍，看谁解出来更像中文
        Charset gb = charset("GB18030");
        Charset big5 = charset("Big5");
        if (gb == null) return big5 != null ? big5 : StandardCharsets.UTF_8;
        if (big5 == null) return gb;

        return score(bytes, length, big5) > score(bytes, length, gb) ? big5 : gb;
    }

    /**
     * 这一段字节按这个编码解出来，有多"像正常中文小说"。
     *
     * 数的是<b>常见字符</b>占的比例：汉字、中文标点、全角符号、ASCII 可打印字符、
     * 换行。解错编码时会大量出现三类东西——替换字符 �、控制字符、以及生僻到正文里
     * 不可能成片出现的字（私用区、罕用假名区段等），它们一个都不计分，还要倒扣。
     *
     * 倒扣是要紧的：只加分的话，两种编码都能解出一堆"合法但没意义"的字，分数拉不开。
     */
    static int score(byte[] bytes, int length, Charset charset) {
        String text = new String(bytes, 0, length, charset);

        int good = 0, bad = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '\n' || c == '\r' || c == '\t') { good++; continue; }
            if (c == 0xFFFD) { bad += 3; continue; }              // 解不出来的字节
            if (c < 0x20) { bad += 3; continue; }                 // 控制字符：正文里不该有
            if (c < 0x7F) { good++; continue; }                   // ASCII 可打印
            if (c >= 0x4E00 && c <= 0x9FFF) { good += 2; continue; }   // 汉字，正文的主体
            if (c >= 0x3000 && c <= 0x303F) { good += 2; continue; }   // 中文标点 —— 。！？「」
            if (c >= 0xFF00 && c <= 0xFFEF) { good++; continue; }      // 全角字母数字与标点
            if (c >= 0xE000 && c <= 0xF8FF) { bad += 2; continue; }    // 私用区：解错时的常客
            bad++;                                                     // 其余的都算"不像正文"
        }
        return good - bad;
    }

    /**
     * 这一段字节是不是合法的 UTF-8。
     *
     * 自己走一遍解码器而不是 {@code new String(...)} 再看有没有 �：后者分不清
     * "文件本来就有 �" 与 "解码失败"，而前者用 REPORT 策略，第一个非法序列就抛。
     *
     * 末尾那几个字节可能被 {@link #SAMPLE} 截断在一个多字节序列中间 —— 那不算错，
     * 所以截断处往回退最多 3 个字节再判。
     */
    static boolean isValidUtf8(byte[] bytes, int length) {
        int end = length;
        if (end < bytes.length) {
            // 退到最后一个"不是续字节"的位置，把可能被截断的那个序列整个剔掉
            int back = 0;
            while (end > 0 && back < 4 && (bytes[end - 1] & 0xC0) == 0x80) { end--; back++; }
            if (end > 0) end--;      // 那个多字节序列的首字节本身也剔掉
        }
        if (end <= 0) return true;

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes, 0, end));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** BOM 占几个字节，没有就是 0。解码时要跳过它，否则正文第一个字是个看不见的 ﻿ */
    private static int bomLength(byte[] bytes, Charset charset) {
        if (startsWith(bytes, BOM_UTF8)) return BOM_UTF8.length;
        if (charset == StandardCharsets.UTF_16LE || charset == StandardCharsets.UTF_16BE) return 2;
        return 0;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) return false;
        }
        return true;
    }

    /**
     * 取一个可能不存在的编码。
     *
     * GB18030 与 Big5 在 JDK 里属于 {@code jdk.charsets} 模块 —— 绝大多数整合包用的
     * 完整 JRE 都带着，但有人会用裁过的运行时。取不到就当它不存在，让另一种去解，
     * 而不是在这儿抛一个玩家看不懂的异常。
     */
    private static Charset charset(String name) {
        try {
            return Charset.isSupported(name) ? Charset.forName(name) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
