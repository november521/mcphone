package com.november.mcphone.feature.music;

/**
 * 循环模式 —— 一首放完之后干什么。
 *
 * 四种就够了，和常见手机播放器一致。刻意不做"播完退出"之类的变体：
 * 每多一种，界面上那个按钮就要多切一次才能转回来。
 */
public enum PlayMode {

    /** 顺序播放：放到最后一首就停 */
    SEQUENTIAL("mcphone.music.mode.sequential", "→"),

    /** 列表循环：最后一首放完回到第一首 */
    LIST_LOOP("mcphone.music.mode.list_loop", "↻"),

    /** 单曲循环：一直放这一首 */
    SINGLE_LOOP("mcphone.music.mode.single_loop", "①"),

    /** 随机：每次从列表里另挑一首 */
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

    /**
     * 没有贴图时画的字符。
     *
     * 与导航栏三个键、传送图标同一套做法：贴图优先、字符兜底，
     * 兜底也要能看懂是什么。
     */
    public String glyph() {
        return glyph;
    }

    /** 按钮点一下切到下一种，转一圈回来 */
    public PlayMode next() {
        PlayMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
