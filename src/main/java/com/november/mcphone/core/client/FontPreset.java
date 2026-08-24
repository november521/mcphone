package com.november.mcphone.core.client;

import java.util.Locale;

/**
 * 字体颜色预设 —— 玩家在「设置 → 字体颜色」里挑的那几个。
 *
 * 一个预设为什么只有两个颜色
 *
 * 手机上画在壁纸上的中性字色一共八档（标题、正文、次要、时间戳……），
 * 它们的现有取值不是随便挑的，而是一条等差灰阶：
 *
 *     FFFFFF  EEEEEE  CCCCCC  BBBBBB  AAAAAA  999999  888888  777777  666666  555555
 *
 * 全落在 0x55 与 0xFF 之间，步长正好 0x11。既然如此，预设就不必为每一档
 * 手写一个值——只给两端，中间按「明显度」插值即可。八档少写四十多个数字，
 * 而且再也不会出现"改了标题忘了改正文"这种半拉子配色。
 *
 * {@link #WHITE} 的两端取 555555 与 FFFFFF，插出来的八个值与本功能落地前
 * 逐字节相同。这是刻意的：不选，就一模一样。
 *
 * 深色字的预设为什么要另一套语义色
 *
 * 危险红 FF8888、确认绿 66FF88 这些是给深底配的。玩家把字调成黑色，
 * 意味着他用的是浅色壁纸——那几个亮色在白底上会淡得几乎看不见。
 *
 * 所以语义色备了深浅两套，选哪套不另开一个开关，由 {@link #darkText()}
 * 从最亮端的亮度算出来。开关会跟颜色对不上（有人把两端设成黑白却把开关
 * 写反），算出来的不会。
 */
public enum FontPreset {

    /** 默认。与本功能落地前的配色逐字节相同 */
    WHITE(0xFFFFFF, 0x555555),

    /** 配浅色壁纸用。最亮端是纯黑，最暗端往中灰走 */
    BLACK(0x000000, 0x707070),

    AMBER(0xFFDCA8, 0x7C6244),

    CYAN(0xC2ECFF, 0x4C6F80),

    MINT(0xC9F3C4, 0x4F7A4C),

    PINK(0xFFCBE0, 0x7E5A6C);

    /** 最显眼那一档的颜色（标题）。RGB，不含 alpha */
    private final int strong;

    /** 最不显眼那一档的颜色（点不动的字）。RGB，不含 alpha */
    private final int weak;

    private final boolean darkText;

    FontPreset(int strong, int weak) {
        this.strong = strong;
        this.weak = weak;

        // ITU-R BT.601 亮度。用感知亮度而不是简单平均：同样的数值，绿最亮、
        // 蓝最暗，平均值会把 0000FF 判成"中等亮"，而它在白底上其实很暗
        int r = (strong >> 16) & 0xFF;
        int g = (strong >> 8) & 0xFF;
        int b = strong & 0xFF;
        this.darkText = (r * 299 + g * 587 + b * 114) / 1000 < 128;
    }

    /** 最显眼那一档。也是设置页里那个色块显示的颜色 */
    public int strong() { return strong; }

    /** 最不显眼那一档 */
    public int weak() { return weak; }

    /**
     * 这套字是深色的吗 —— 是的话，说明玩家配的是浅色底，语义色要用深的那套。
     */
    public boolean darkText() { return darkText; }

    /** 配置文件里写的字符串，也是设置页排序之外的稳定标识 */
    public String id() { return name().toLowerCase(Locale.ROOT); }

    /** 面向玩家的名字，中英各一份在语言文件里 */
    public String translationKey() { return "mcphone.font_preset." + id(); }
}
