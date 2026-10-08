package com.november.mcphone.feature.chat.client;

import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.resources.language.FormattedBidiReorder;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** 使用真实 StringSplitter 与确定字宽，验证命中、原文复制、字素边界与消息生命周期。 */
public final class ChatTextSelectionTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static void equal(Object actual, Object expected, String message) {
        check(expected.equals(actual), message + "：" + actual + " != " + expected);
    }
    private static class TestFont extends Font {
        private final StringSplitter metrics = new StringSplitter((cp, style) -> switch (cp) {
            case 'i' -> 2; case 'W' -> 9; case '\u0301', '\u200D', '\uFE0F' -> 0; default -> 6;
        });
        TestFont() { super(id -> null, false); }
        @Override public StringSplitter getSplitter() { return metrics; }
        @Override public boolean isBidirectional() { return false; }
        @Override public int width(FormattedCharSequence text) { return (int) Math.ceil(metrics.stringWidth(text)); }
        @Override public List<FormattedCharSequence> split(FormattedText text, int width) {
            return metrics.splitLines(text, width, Style.EMPTY).stream()
                    .map(line -> FormattedCharSequence.forward(line.getString(), Style.EMPTY)).toList();
        }
    }

    public static void main(String[] args) {
        Font font = new TestFont();
        var text = ChatTextLayout.build(font, "哎呀好想吃辣条", 100);
        equal(text.lines().size(), 1, "短中文保持一行");
        equal(text.indexAt(12, 0, 7), 2, "点击汉字边界映射到原文");
        equal(text.indexAt(17.9, 0, 7), 3, "半字按最近边界吸附");
        var selection = new ChatTextSelection();
        Object message = new Object();
        selection.begin(message, text, text.indexAt(12, 0, 7));
        selection.extend(text.indexAt(30, 0, 7));
        equal(selection.selectedText(), "好想吃", "只复制气泡局部文字");
        check(selection.release() && !selection.dragging(), "松手保留选区并结束拖动");
        selection.extend(0);
        equal(selection.selectedText(), "好想吃", "松手后的鼠标移动不更改选区");
        check(!selection.release(), "没有拖动时不截获松手");
        selection.begin(message, text, 5);
        selection.extend(2);
        equal(selection.selectedText(), "好想吃", "反向拖选得到相同原文");
        equal(text.lines().get(0).spans(selection.start(), selection.end()),
                List.of(new ChatTextLayout.Span(12, 30)), "高亮只覆盖所选字宽");

        var uneven = ChatTextLayout.build(font, "Wi中", 100);
        equal(uneven.indexAt(8, 0, 7), 1, "宽字按实际 advance 命中");
        equal(uneven.indexAt(10.5, 0, 7), 2, "窄字按实际 advance 命中");
        equal(uneven.lines().get(0).spans(1, 2), List.of(new ChatTextLayout.Span(9, 11)), "窄字高亮宽度为二");
        equal(uneven.indexAt(-100, 0, 7), 0, "拖出左边界收敛到行首");
        equal(uneven.indexAt(100, 0, 7), 3, "拖出右边界收敛到行尾");

        String original = "hello world world";
        var wrapped = ChatTextLayout.build(font, original, 36);
        check(wrapped.lines().size() >= 3, "真实原版算法执行软换行");
        equal(wrapped.lines().get(1).start(), 6, "保留换行处被原版隐藏的空格位置");
        selection.begin(message, wrapped, 0);
        selection.extend(wrapped.indexAt(0, 1000, 7));
        equal(selection.selectedText(), original, "跨软换行复制保留空格且不插入换行");
        var repeated = ChatTextLayout.build(font, "abcabcabc", 18);
        equal(repeated.lines().get(2).start(), 6, "重复子串使用原文索引而非字符串搜索");

        var multiline = ChatTextLayout.build(font, "你好\n\n世界\n", 100);
        equal(multiline.lines().size(), 4, "保留显式空行及末尾换行");
        equal(multiline.indexAt(0, 7, 7), 3, "空行仍有原文光标位置");
        selection.begin(message, multiline, 1);
        selection.extend(multiline.indexAt(100, 14, 7));
        equal(selection.selectedText(), "好\n\n世界", "显式换行原样复制");
        equal(multiline.indexAt(100, -1, 7), 0, "拖到消息上方选择至原文开头");
        equal(multiline.indexAt(0, 28, 7), 7, "拖到消息下方包含末尾换行");
        var empty = ChatTextLayout.build(font, "", 100);
        check(!empty.selectable() && empty.lines().size() == 1, "空文本不可选择但有安全空行");

        for (String cluster : List.of("\uD83D\uDE00", "e\u0301", "\uD83D\uDC69\u200D\uD83D\uDCBB",
                "\uD83C\uDDE8\uD83C\uDDF3", "\uD83D\uDC4D\uD83C\uDFFD")) {
            var unicode = ChatTextLayout.build(font, "a" + cluster + "b", 100);
            var glyphs = unicode.lines().get(0).glyphs();
            equal(glyphs.size(), 3, "组合字符合并成一个命中区");
            equal(glyphs.get(1).start(), 1, "字素起点不落在代理对/组合序列内");
            equal(glyphs.get(1).end(), 1 + cluster.length(), "字素终点包含完整序列");
            selection.begin(message, unicode, glyphs.get(1).start());
            selection.extend(glyphs.get(1).end());
            equal(selection.selectedText(), cluster, "emoji 与重音原样复制");
            var tiny = ChatTextLayout.build(font, "a" + cluster + "b", 6);
            for (var line : tiny.lines()) for (var glyph : line.glyphs()) {
                check(glyph.start() <= 1 || glyph.start() >= 1 + cluster.length(), "跨行字素也不暴露内部起点");
                check(glyph.end() <= 1 || glyph.end() >= 1 + cluster.length(), "跨行字素也不暴露内部终点");
            }
        }

        Font rtlFont = new TestFont() {
            @Override public List<FormattedCharSequence> split(FormattedText text, int width) {
                return getSplitter().splitLines(text, width, Style.EMPTY).stream()
                        .map(line -> FormattedBidiReorder.reorder(line, false)).toList();
            }
        };
        var rtl = ChatTextLayout.build(rtlFont, "אבג", 100);
        equal(rtl.indexAt(0, 0, 7), 3, "RTL 左边是原文末尾");
        equal(rtl.indexAt(18, 0, 7), 0, "RTL 右边是原文开头");
        equal(rtl.lines().get(0).spans(0, 1), List.of(new ChatTextLayout.Span(12, 18)), "RTL 高亮跟随视觉顺序");
        var palindrome = ChatTextLayout.build(rtlFont, "אבא", 100);
        equal(palindrome.indexAt(0, 0, 7), 3, "双向回文也必须保留 RTL 方向");
        var mixed = ChatTextLayout.build(rtlFont, "abc אבג def", 100);
        equal(mixed.indexAt(24, 0, 7), 4, "双向游程边界采用相邻的稳定插入点");
        equal(mixed.indexAt(25, 0, 7), 7, "混排 RTL 游程左侧对应末尾");
        equal(mixed.lines().get(0).spans(4, 5), List.of(new ChatTextLayout.Span(36, 42)), "混排高亮原文首个希伯来字");
        var rtlFirst = ChatTextLayout.build(rtlFont, "אבג abc", 100);
        equal(rtlFirst.lines().get(0).glyphs().get(0).start(), 4, "RTL 段落中的英文按实际视觉游程排列");

        selection.begin(message, text, 1);
        selection.extend(4);
        selection.reconcile(List.of(new Object(), message, new Object()));
        equal(selection.selectedText(), "呀好想", "新消息和下标变动不串选区");
        selection.reconcile(List.of(new Object()));
        check(selection.selectedText().isEmpty() && !selection.dragging(), "消息淘汰同时清理拖动与复制源");
        String duplicate1 = new String("same"), duplicate2 = new String("same");
        selection.begin(duplicate1, text, 0);
        check(!selection.owns(duplicate2), "内容相同的消息仍按对象身份隔离");
        selection.extend(2);
        selection.clear();
        check(selection.selectedText().isEmpty() && !selection.owns(duplicate1), "切换会话清除旧选区");
        try { text.lines().clear(); throw new AssertionError("行列表必须不可变"); }
        catch (UnsupportedOperationException expected) { checks++; }
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
