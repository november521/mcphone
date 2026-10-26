package com.november.mcphone.core.script.client.render;

import com.november.mcphone.core.script.layout.TextMeasure;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Bounded multiline editing shared by every target. Positions and limits use UTF-16 boundaries. */
final class TextAreaEditor {
    record Line(int start, int end) {}

    private final TextMeasure measure;
    private final Predicate<String> commit;
    private final List<Line> lines = new ArrayList<>();
    private String value = "";
    private int cursor, anchor, max, width, height;
    private int probeWidth = -1, measuredLineHeight;
    private int scroll;
    private int preferredX = -1;
    private boolean lineEndAffinity;
    private long blinkAt = System.currentTimeMillis();

    TextAreaEditor(TextMeasure measure, Predicate<String> commit) {
        this.measure = measure;
        this.commit = commit;
    }

    void sync(String text, int limit, int w, int h) {
        max = limit;
        int probe = measure.width("MCphone 字体");
        boolean changed = !value.equals(text), resized = width != w || height != h
                || probeWidth != probe || measuredLineHeight != measure.lineHeight();
        probeWidth = probe;
        measuredLineHeight = measure.lineHeight();
        width = Math.max(1, w);
        height = Math.max(1, h);
        if (changed) {
            value = text;
            cursor = boundary(Math.min(cursor, value.length()));
            anchor = boundary(Math.min(anchor, value.length()));
        }
        if (changed || resized || lines.isEmpty()) {
            preferredX = -1;
            reflow();
            reveal();
        }
    }

    String value() { return value; }
    int cursor() { return cursor; }
    int anchor() { return anchor; }
    int scroll() { return scroll; }
    int lineHeight() { return Math.max(1, measure.lineHeight()); }
    int contentHeight() { return lines.size() * lineHeight(); }
    List<Line> lines() { return lines; }
    boolean caretVisible() { return (System.currentTimeMillis() - blinkAt) / 500 % 2 == 0; }

    /** Filter before insertion, so truncation cannot remove the unselected suffix or split an emoji. */
    static String normalize(String text, int limit) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), Math.max(0, limit)));
        for (int i = 0; i < text.length() && out.length() < limit;) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\r') {
                if (i < text.length() && text.charAt(i) == '\n') i++;
                cp = '\n';
            }
            if (cp == '\t') cp = ' ';
            if (cp != '\n' && (Character.isISOControl(cp) || cp == 0xa7)) continue;
            if (cp >= 0xd800 && cp <= 0xdfff) continue;
            if (out.length() + Character.charCount(cp) > limit) break;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    void insert(String text) {
        int start = Math.min(cursor, anchor), end = Math.max(cursor, anchor);
        String insertion = normalize(text, Math.max(0, max - value.length() + end - start));
        if (insertion.isEmpty() && !text.isEmpty()) return;
        replace(start, end, insertion);
    }

    private void replace(int start, int end, String insertion) {
        String next = value.substring(0, start) + insertion + value.substring(end);
        if (!commit.test(next)) return; // rejected page budget leaves value AND selection intact
        value = next;
        cursor = anchor = start + insertion.length();
        preferredX = -1;
        reflow();
        reveal();
        blinkAt = System.currentTimeMillis();
    }

    void key(int key, boolean control, boolean shift, Supplier<String> clipboard, Consumer<String> copy) {
        if (control) {
            switch (key) {
                case 65 -> { anchor = 0; cursor = value.length(); reveal(); return; }
                case 67 -> { if (cursor != anchor) copy.accept(selected()); return; }
                case 88 -> { if (cursor != anchor) { copy.accept(selected()); insert(""); } return; }
                case 86 -> { insert(clipboard.get()); return; }
                default -> { }
            }
        }
        switch (key) {
            case 257, 335 -> insert("\n");
            case 259, 261 -> {
                int direction = key == 259 ? -1 : 1;
                int start = Math.min(cursor, anchor), end = Math.max(cursor, anchor);
                if (start == end) {
                    int other = control ? word(direction) : step(cursor, direction);
                    start = Math.min(cursor, other); end = Math.max(cursor, other);
                }
                replace(start, end, "");
            }
            case 262, 263 -> {
                int direction = key == 263 ? -1 : 1;
                int next = !shift && cursor != anchor ? (direction < 0 ? Math.min(cursor, anchor) : Math.max(cursor, anchor))
                        : control ? word(direction) : step(cursor, direction);
                move(next, shift);
                preferredX = -1;
            }
            case 268, 269 -> {
                Line line = lines.get(cursorLine());
                move(control ? (key == 268 ? 0 : value.length()) : (key == 268 ? line.start : line.end), shift,
                        !control && key == 269);
                preferredX = -1;
            }
            case 264, 265, 266, 267 -> {
                int row = cursorLine();
                if (preferredX < 0) preferredX = measure.width(value.substring(lines.get(row).start, cursor));
                int distance = key == 266 || key == 267 ? Math.max(1, height / lineHeight()) : 1;
                int next = Math.max(0, Math.min(lines.size() - 1, row + (key == 265 || key == 266 ? -distance : distance)));
                int position = atX(lines.get(next), preferredX);
                move(position, shift, position == lines.get(next).end);
            }
            default -> { }
        }
    }

    private String selected() { return value.substring(Math.min(cursor, anchor), Math.max(cursor, anchor)); }

    private int boundary(int position) {
        return position > 0 && position < value.length() && Character.isLowSurrogate(value.charAt(position))
                && Character.isHighSurrogate(value.charAt(position - 1)) ? position - 1 : position;
    }

    private int step(int from, int direction) {
        if (direction < 0) return from == 0 ? 0 : value.offsetByCodePoints(from, -1);
        return from == value.length() ? from : value.offsetByCodePoints(from, 1);
    }

    private int word(int direction) {
        int at = cursor;
        if (direction < 0) {
            while (at > 0 && Character.isWhitespace(value.codePointBefore(at))) at = step(at, -1);
            while (at > 0 && !Character.isWhitespace(value.codePointBefore(at))) at = step(at, -1);
        } else {
            while (at < value.length() && !Character.isWhitespace(value.codePointAt(at))) at = step(at, 1);
            while (at < value.length() && Character.isWhitespace(value.codePointAt(at))) at = step(at, 1);
        }
        return at;
    }

    void point(double x, double y, boolean selecting) {
        int row = (int) Math.max(0, Math.min(lines.size() - 1, Math.floor((y + scroll) / lineHeight())));
        int position = atX(lines.get(row), x);
        move(position, selecting, position == lines.get(row).end);
        preferredX = -1;
    }

    void drag(double x, double y) {
        if (y < 0) scroll(1);
        else if (y >= height) scroll(-1);
        point(x, Math.max(0, Math.min(height - 1, y)), true);
    }

    void scroll(double amount) {
        if (!Double.isFinite(amount) || amount == 0) return;
        double next = scroll - amount * lineHeight() * 2;
        scroll = (int) Math.max(0, Math.min(Math.max(0, contentHeight() - height), next));
    }

    private void move(int next, boolean selecting) {
        move(next, selecting, false);
    }

    private void move(int next, boolean selecting, boolean lineEnd) {
        cursor = boundary(next);
        lineEndAffinity = lineEnd;
        if (!selecting) anchor = cursor;
        reveal();
        blinkAt = System.currentTimeMillis();
    }

    int cursorLine() {
        for (int i = 0; i < lines.size() - 1; i++) {
            Line line = lines.get(i);
            if (cursor < line.end || cursor == line.end && (lineEndAffinity || lines.get(i + 1).start > cursor)) return i;
        }
        return lines.size() - 1;
    }

    private int atX(Line line, double x) {
        int px = 0;
        for (int i = line.start; i < line.end;) {
            int next = step(i, 1);
            int w = Math.max(0, measure.width(value.substring(i, next)));
            if (x < px + w / 2.0) return i;
            px += w;
            i = next;
        }
        return line.end;
    }

    private void reflow() {
        lineEndAffinity = false;
        lines.clear();
        int start = 0, px = 0;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i), next = i + Character.charCount(cp);
            if (cp == '\n') {
                lines.add(new Line(start, i));
                start = next; px = 0;
            } else {
                int w = Math.max(0, measure.width(value.substring(i, next)));
                if (i > start && (long) px + w > width) {
                    lines.add(new Line(start, i));
                    start = i; px = 0;
                }
                px += w;
            }
            i = next;
        }
        lines.add(new Line(start, value.length()));
        scroll = Math.min(scroll, Math.max(0, contentHeight() - height));
    }

    private void reveal() {
        int top = cursorLine() * lineHeight();
        if (top < scroll) scroll = top;
        if (top + lineHeight() > scroll + height) scroll = top + lineHeight() - height;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight() - height)));
    }
}
