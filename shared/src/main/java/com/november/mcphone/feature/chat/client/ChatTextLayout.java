package com.november.mcphone.feature.chat.client;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.text.Bidi;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 原版折行与原文区间的映射。绘制仍用原版视觉序列，复制只截原文，不插入软换行。 */
final class ChatTextLayout {
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    record Glyph(int start, int end, float left, float right, boolean reversed) {}
    private record SourceGlyph(int index, boolean reversed) {}
    private record Run(int start, int end, byte level) {}
    record Span(float left, float right) {}
    record Range(int start, int end) {}
    record Line(int start, int end, FormattedCharSequence text, List<Glyph> glyphs, float width) {
        Line { glyphs = List.copyOf(glyphs); }

        /** 鼠标靠近字的哪一侧就落在哪一侧；同一组合字符不能拆成半个 emoji 或重音。 */
        int indexAt(double x) {
            // 双向游程交界处可以有两个原文插入点；进入字形内部后必须采用该字的方向。
            for (Glyph glyph : glyphs) {
                if (x > glyph.left() && x < glyph.right()) {
                    boolean left = x - glyph.left() <= glyph.right() - x;
                    return left != glyph.reversed() ? glyph.start() : glyph.end();
                }
            }
            int best = start;
            double distance = Double.POSITIVE_INFINITY;
            for (Glyph glyph : glyphs) {
                double dl = Math.abs(x - glyph.left());
                if (dl < distance) { distance = dl; best = glyph.reversed() ? glyph.end() : glyph.start(); }
                double dr = Math.abs(x - glyph.right());
                if (dr < distance) { distance = dr; best = glyph.reversed() ? glyph.start() : glyph.end(); }
            }
            return best;
        }

        List<Span> spans(int from, int to) {
            List<Span> result = new ArrayList<>();
            for (Glyph glyph : glyphs) {
                if (glyph.start() >= to || glyph.end() <= from) continue;
                if (!result.isEmpty() && result.get(result.size() - 1).right() == glyph.left()) {
                    Span previous = result.remove(result.size() - 1);
                    result.add(new Span(previous.left(), glyph.right()));
                } else result.add(new Span(glyph.left(), glyph.right()));
            }
            return List.copyOf(result);
        }
    }

    private final String source;
    private final List<Line> lines;
    private ChatTextLayout(String source, List<Line> lines) {
        this.source = source;
        this.lines = List.copyOf(lines);
    }
    String source() { return source; }
    List<Line> lines() { return lines; }
    List<FormattedCharSequence> visualLines() { return lines.stream().map(Line::text).toList(); }
    boolean selectable() {
        return !source.isEmpty() && lines.stream().allMatch(l -> l.start() == l.end() || !l.glyphs().isEmpty());
    }

    static ChatTextLayout build(Font font, String source, int maxWidth) {
        List<Range> ranges = new ArrayList<>();
        font.getSplitter().splitLines(source, maxWidth, Style.EMPTY, false,
                (style, start, end) -> ranges.add(new Range(start, end)));
        // 字符串版 splitLines 不输出末尾空行，Component 版会输出，补齐同一份索引。
        if (source.isEmpty() || source.endsWith("\n")) ranges.add(new Range(source.length(), source.length()));
        List<FormattedCharSequence> visual = font.split(Component.literal(source), maxWidth);
        if (visual.isEmpty()) visual = List.of(FormattedCharSequence.EMPTY);
        if (ranges.size() != visual.size()) throw new IllegalStateException("聊天文本折行与原文区间不一致");
        List<Range> clusters = new ArrayList<>();
        var matcher = GRAPHEME.matcher(source);
        while (matcher.find()) clusters.add(new Range(matcher.start(), matcher.end()));
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) {
            Range range = ranges.get(i);
            lines.add(line(font, source, range, visual.get(i), clusters));
        }
        return new ChatTextLayout(source, lines);
    }

    private static Line line(Font font, String source, Range range, FormattedCharSequence visual, List<Range> clusters) {
        String raw = source.substring(range.start(), range.end());
        List<SourceGlyph> positions = new ArrayList<>();
        for (int p = 0; p < raw.length(); p += Character.charCount(raw.codePointAt(p))) {
            positions.add(new SourceGlyph(p, false));
        }
        // 视觉序列的 sink 索引会在游程间重置，不能用它当原文位置；按双向游程恢复。
        // 即使正反读出来相同，也必须保留 RTL 的光标方向。
        if (Bidi.requiresBidi(raw.toCharArray(), 0, raw.length())) {
            Bidi bidi = new Bidi(raw, font.isBidirectional()
                    ? Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT : Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
            List<SourceGlyph> reordered = new ArrayList<>();
            Object[] runs = new Object[bidi.getRunCount()];
            byte[] levels = new byte[runs.length];
            for (int run = 0; run < bidi.getRunCount(); run++) {
                levels[run] = (byte) bidi.getRunLevel(run);
                runs[run] = new Run(bidi.getRunStart(run), bidi.getRunLimit(run), levels[run]);
            }
            Bidi.reorderVisually(levels, 0, runs, 0, runs.length);
            for (Object value : runs) {
                Run run = (Run) value;
                List<SourceGlyph> part = positions.stream().filter(p -> p.index() >= run.start() && p.index() < run.end()).toList();
                if ((run.level() & 1) != 0) {
                    for (int j = part.size() - 1; j >= 0; j--) reordered.add(new SourceGlyph(part.get(j).index(), true));
                } else reordered.addAll(part);
            }
            positions = reordered;
        }
        final List<SourceGlyph> offsets = positions;
        List<Glyph> glyphs = new ArrayList<>();
        float[] x = {0};
        int[] cursor = {0};
        visual.accept((index, style, cp) -> {
            float advance = font.getSplitter().stringWidth(FormattedCharSequence.codepoint(cp, style));
            if (cursor[0] < offsets.size()) {
                SourceGlyph position = offsets.get(cursor[0]);
                int start = range.start() + position.index();
                int end = start + Character.charCount(source.codePointAt(start));
                for (Range cluster : clusters) {
                    if (cluster.start() <= start && start < cluster.end()) {
                        start = cluster.start(); end = cluster.end(); break;
                    }
                }
                // 连续字形属于同一字素时合成一个命中区，不能在组合字符内部跳动。
                if (!glyphs.isEmpty() && glyphs.get(glyphs.size() - 1).start() == start
                        && glyphs.get(glyphs.size() - 1).end() == end) {
                    Glyph previous = glyphs.remove(glyphs.size() - 1);
                    glyphs.add(new Glyph(start, end, previous.left(), x[0] + advance, position.reversed()));
                } else glyphs.add(new Glyph(start, end, x[0], x[0] + advance, position.reversed()));
            }
            cursor[0]++;
            x[0] += advance;
            return true;
        });
        // 字体/语言若改变字形数量就没有可靠的一一映射，保留显示并禁用该行命中，避免复制错字。
        if (cursor[0] != offsets.size()) glyphs.clear();
        return new Line(range.start(), range.end(), visual, glyphs, x[0]);
    }

    int indexAt(double x, double y, int lineStep) {
        if (y < 0) return 0;
        if (y >= lines.size() * (double) lineStep) return source.length();
        return lines.get(Math.min(lines.size() - 1, (int) (y / lineStep))).indexAt(x);
    }
}
