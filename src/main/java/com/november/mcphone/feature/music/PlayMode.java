package com.november.mcphone.feature.music;

/** 循环模式，一首放完之后干什么。故意没有"顺序播放"（放完一轮就停）：那属于定时关闭，不该混进循环模式 */
public enum PlayMode {

    /** 默认 */
    LIST_LOOP("mcphone.music.mode.list_loop", "↻"),

    SINGLE_LOOP("mcphone.music.mode.single_loop", "①"),

    SHUFFLE("mcphone.music.mode.shuffle", "⇄");

    private final String key;
    private final String glyph;

    PlayMode(String key, String glyph) {
        this.key = key;
        this.glyph = glyph;
    }

    /** 界面上那个按钮的翻译键（悬停提示用） */
    public String translationKey() {
        return key;
    }

    /** 没有贴图时画的字符；贴图优先、字符兜底 */
    public String glyph() {
        return glyph;
    }

    /** 按钮点一下切到下一种，转一圈回来 */
    public PlayMode next() {
        PlayMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
