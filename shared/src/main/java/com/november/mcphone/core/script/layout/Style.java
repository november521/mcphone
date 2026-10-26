package com.november.mcphone.core.script.layout;

/**
 * 一个节点叠完四层之后的样式（施工方案 §6.3、§6.5）。不可变。
 *
 * <p>怎么叠见 {@link Stylesheet#resolve}；属性名到字段的对应只写在 {@link MssParser} 的 SPECS 表里。
 */
public final class Style {

    // 这四个枚举的常量名小写就是 MSS 里的关键字（SPECS 由它们生成），改名等于改语法。

    public enum Layout { COLUMN, ROW, STACK }

    public enum Align { START, CENTER, END, STRETCH }

    public enum Justify { START, CENTER, END, BETWEEN }

    public enum TextAlign { LEFT, CENTER, RIGHT }

    /** width / height 的值。只有 FIXED 带像素数，其余 value 为 0。 */
    public record SizeSpec(Kind kind, int value) {

        public enum Kind { AUTO, FILL, FIXED }

        public static final SizeSpec AUTO = new SizeSpec(Kind.AUTO, 0);
        public static final SizeSpec FILL = new SizeSpec(Kind.FILL, 0);

        public static SizeSpec fixed(int px) {
            return new SizeSpec(Kind.FIXED, px);
        }
    }

    /** 语义色（§6.6），与 PhoneStyle 的十个方法一一对应。只存名字：存成 ARGB 的话，换了皮肤颜色还是旧的。 */
    public enum Token {
        TITLE("$title"),
        BODY("$body"),
        SUBTLE("$subtle"),
        ACCENT("$accent"),
        SCREEN("$screen"),
        PRESSED("$pressed"),
        BUTTON("$button"),
        BUTTON_HOVER("$button-hover"),
        BUTTON_DISABLED("$button-disabled"),
        BUTTON_DISABLED_TEXT("$button-disabled-text");

        /** 在 MSS 里的写法，带 $。 */
        public final String mss;

        Token(String mss) {
            this.mss = mss;
        }

        public static Token of(String mss) {
            for (Token t : values()) {
                if (t.mss.equals(mss)) return t;
            }
            return null;
        }

        /** 全部写法，按表的顺序以空格分隔。 */
        public static String all() {
            StringBuilder sb = new StringBuilder();
            for (Token t : values()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(t.mss);
            }
            return sb.toString();
        }
    }

    /** {@code max-lines: none}。 */
    public static final int NO_MAX_LINES = 0;

    private final Layout layout;
    private final SizeSpec width;
    private final SizeSpec height;
    private final int grow;
    private final int padTop;
    private final int padRight;
    private final int padBottom;
    private final int padLeft;
    private final int gap;
    private final Align align;
    private final Justify justify;
    private final Token background;
    private final Token hoverBackground;
    private final Token color;
    private final Token hoverColor;
    private final int border;
    private final boolean shadow;
    private final TextAlign textAlign;
    private final boolean wrap;
    private final int maxLines;
    private final boolean hidden;

    private Style(Builder b) {
        layout = b.layout;
        width = b.width;
        height = b.height;
        grow = b.grow;
        padTop = b.padTop;
        padRight = b.padRight;
        padBottom = b.padBottom;
        padLeft = b.padLeft;
        gap = b.gap;
        align = b.align;
        justify = b.justify;
        background = b.background;
        hoverBackground = b.hoverBackgroundSet ? b.hoverBackground : b.background;
        color = b.color;
        hoverColor = b.hoverColorSet ? b.hoverColor : b.color;
        border = b.border;
        shadow = b.shadow;
        textAlign = b.textAlign;
        wrap = b.wrap;
        maxLines = b.maxLines;
        hidden = b.hidden;
    }

    /** 只有 box / button 按它排。其余类型的默认值与类型一致，不一致说明作者写了会被忽略的 layout（§6.5 要 warn）。 */
    public Layout layout() {
        return layout;
    }

    public SizeSpec width() {
        return width;
    }

    public SizeSpec height() {
        return height;
    }

    public int grow() {
        return grow;
    }

    public int padTop() {
        return padTop;
    }

    public int padRight() {
        return padRight;
    }

    public int padBottom() {
        return padBottom;
    }

    public int padLeft() {
        return padLeft;
    }

    public int gap() {
        return gap;
    }

    public Align align() {
        return align;
    }

    public Justify justify() {
        return justify;
    }

    /** 底色，null 表示 none。 */
    public Token background() {
        return background;
    }

    /** 悬停时的底色，null 表示 none。没写 hover-background 时等于 {@link #background()}，所以悬停时直接用它。 */
    public Token hoverBackground() {
        return hoverBackground;
    }

    public Token color() {
        return color;
    }

    /** 悬停时的文字色。没写 hover-color 时等于 {@link #color()}。 */
    public Token hoverColor() {
        return hoverColor;
    }

    public int border() {
        return border;
    }

    public boolean shadow() {
        return shadow;
    }

    public TextAlign textAlign() {
        return textAlign;
    }

    public boolean wrap() {
        return wrap;
    }

    /** 最多几行，{@link #NO_MAX_LINES} 表示不限。 */
    public int maxLines() {
        return maxLines;
    }

    public boolean hidden() {
        return hidden;
    }

    /** 第 1 层：组件类型默认值。§6.5 的「默认」列，加上 §5 给个别类型写明的默认；type 为 null 时只有前者。 */
    static Builder defaults(NodeType type) {
        Builder b = new Builder();
        if (type == null) return b;
        switch (type) {
            case ROW -> b.layout = Layout.ROW;
            case STACK -> b.layout = Layout.STACK;
            case SCROLL, LIST -> b.height = SizeSpec.FILL;      // §6.5 height 备注
            case BUTTON -> {                                     // §5.3、§6.2
                b.padTop = b.padBottom = 2;
                b.padLeft = b.padRight = 6;
                b.background = Token.BUTTON;
                b.hoverBackground(Token.BUTTON_HOVER);
            }
            case TEXT_INPUT -> {
                b.width = SizeSpec.FILL;
                b.padTop = b.padBottom = 3;
                b.padLeft = b.padRight = 3;
                b.background = Token.BUTTON;
                b.hoverBackground(Token.BUTTON_HOVER);
            }
            case DIVIDER -> b.color = Token.SUBTLE;             // §5.2
            default -> { }
        }
        return b;
    }

    /** 叠层用的半成品。只在本包里写：外面拿到的永远是叠完的 {@link Style}。 */
    static final class Builder {
        Layout layout = Layout.COLUMN;
        SizeSpec width = SizeSpec.AUTO;
        SizeSpec height = SizeSpec.AUTO;
        int grow;
        int padTop;
        int padRight;
        int padBottom;
        int padLeft;
        int gap;
        Align align = Align.START;
        Justify justify = Justify.START;
        Token background;
        Token hoverBackground;
        boolean hoverBackgroundSet;
        Token color = Token.BODY;
        Token hoverColor;
        boolean hoverColorSet;
        int border;
        boolean shadow;
        TextAlign textAlign = TextAlign.LEFT;
        boolean wrap = true;
        int maxLines = NO_MAX_LINES;
        boolean hidden;

        Builder hoverBackground(Token t) {
            hoverBackground = t;
            hoverBackgroundSet = true;
            return this;
        }

        Builder hoverColor(Token t) {
            hoverColor = t;
            hoverColorSet = true;
            return this;
        }

        Style build() {
            return new Style(this);
        }
    }
}
