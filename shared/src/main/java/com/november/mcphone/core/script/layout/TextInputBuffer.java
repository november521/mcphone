package com.november.mcphone.core.script.layout;

/** 单行输入模型。光标与选区始终停在 Unicode 码点边界；不把孤立代理字符写入 state。 */
public final class TextInputBuffer {
    private String text;
    private int cursor, anchor;
    private final int max;
    private char pending;

    public TextInputBuffer(String initial, int max) {
        if (max < 1 || max > 4096) throw new IllegalArgumentException("输入长度必须在 1–4096");
        this.max = max; text = clean(initial, max); cursor = anchor = text.length();
    }
    public String text() { return text; }
    public int cursor() { return cursor; }
    public int start() { return Math.min(cursor, anchor); }
    public int end() { return Math.max(cursor, anchor); }
    public String selected() { return text.substring(start(), end()); }
    public void selectAll() { pending = 0; anchor = 0; cursor = text.length(); }
    public void moveTo(int offset, boolean select) {
        pending = 0; cursor = Math.max(0, Math.min(text.length(), offset));
        if (cursor > 0 && cursor < text.length() && Character.isLowSurrogate(text.charAt(cursor))) cursor--;
        if (!select) anchor = cursor;
    }
    public void move(int direction, boolean select) {
        if (!select && start() != end()) { moveTo(direction < 0 ? start() : end(), false); return; }
        moveTo(text.offsetByCodePoints(cursor, direction < 0 ? (cursor == 0 ? 0 : -1)
                : (cursor == text.length() ? 0 : 1)), select);
    }
    public void delete(boolean backwards) {
        pending = 0;
        if (start() == end()) {
            int old = cursor;
            move(backwards ? -1 : 1, true);
            anchor = old;
        }
        replace("");
    }
    public void type(char c) {
        if (Character.isHighSurrogate(c)) { pending = c; return; }
        if (Character.isLowSurrogate(c)) {
            if (pending != 0) { char high = pending; pending = 0; replace(new String(new char[]{high, c})); }
            return;
        }
        pending = 0; replace(String.valueOf(c));
    }
    public void replace(String inserted) {
        pending = 0;
        int left = start(), right = end();
        int room = max - text.codePointCount(0, left) - text.codePointCount(right, text.length());
        String next = clean(inserted, Math.max(0, room));
        text = text.substring(0, left) + next + text.substring(right);
        cursor = anchor = left + next.length();
    }
    /** 只接受可显示文本。换行与控制字符变空格，非法代理字符丢弃，按码点限长。 */
    public static String clean(String input, int max) {
        if (input == null || max == 0) return "";
        StringBuilder out = new StringBuilder(); int count = 0;
        for (int i = 0; i < input.length() && count < max;) {
            int cp = input.codePointAt(i); i += Character.charCount(cp);
            if (cp >= 0xD800 && cp <= 0xDFFF) continue;
            out.appendCodePoint(Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 ? ' ' : cp); count++;
        }
        return out.toString();
    }
}
