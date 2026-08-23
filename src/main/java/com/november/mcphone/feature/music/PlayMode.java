package com.november.mcphone.feature.music;

/**
 * 循环模式 —— 一首放完之后干什么。
 *
 * ================================================================
 * 为什么没有"顺序播放"
 * ================================================================
 *
 * 1.4.26 到 1.4.30 之间有过一个 SEQUENTIAL（放到最后一首就停）。它与列表
 * 循环只在【列表末尾那一刻】有区别，而手机上放歌，放完一轮就静音、要你
 * 再点一次，几乎没人想要——网易云音乐干脆就没有这一档。
 *
 * 砍掉它还顺带省一件事：模式键点一圈从四下变成三下，也不用再向玩家解释
 * "顺序和列表循环有啥区别"。
 *
 * 真想"放完就停"，那是定时关闭那类功能，不该混进循环模式里。
 */
public enum PlayMode {

    /** 列表循环：最后一首放完回到第一首。默认 */
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
