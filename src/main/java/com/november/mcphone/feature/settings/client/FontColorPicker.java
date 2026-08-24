package com.november.mcphone.feature.settings.client;

import com.november.mcphone.core.client.ClientConfig;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.FontPreset;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 字体颜色选择器 —— 「设置 → 字体颜色」点进来的这一页。
 *
 * 渲染嵌在 PhoneScreen 的屏幕区域里，和壁纸选择器同一套路数。
 *
 * 色块为什么不走换肤
 *
 * 项目里每个视觉元素都该贴图优先、纯色兜底，这一页是例外中的例外：色块
 * 显示的【就是那个颜色本身】。给它蒙一张贴图，玩家看到的是贴图的颜色，
 * 选出来的却是另一个——那不叫换肤，叫骗人。
 *
 * 这一页别的地方（行悬停底、分割线、选中项的字）仍走 PhoneTheme 与
 * FontPalette，没有一个写死的颜色。
 *
 * 一块色块为什么画三条
 *
 * 一个预设不是一个颜色，是一条从最显眼到最不显眼的渐变。只画最亮那一档
 * 的话，"琥珀"和"樱粉"在小格子里几乎分不出来，而它们的正文、时间戳差得
 * 很远。三条分别是明显度 10 / 5 / 0，一眼看得出这套配色的跨度。
 */
public final class FontColorPicker {

    // ---- 布局 ----
    private static final int PAD_X = 6;
    private static final int PAD_Y = 2;

    /** 色块总宽，三条各占三分之一 */
    private static final int SWATCH_W = 24;
    private static final int SWATCH_H = 8;

    /** 色块与名字之间的空隙 */
    private static final int SWATCH_GAP = 5;

    /** 明显度的最高档，见 FontPalette。色块从这一档渐变到 0 */
    private static final int MAX_PROMINENCE = 10;

    /** 当前生效的那一项右侧的标记 */
    private static final String CURRENT_MARK = "✔";

    /** 鼠标停在第几个预设上，-1 表示不在任何一个上 */
    private int hoveredIdx = -1;

    //  渲染

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        final int x = phoneLeft + PAD_X;
        final int w = screenW - PAD_X * 2;
        final int bottom = phoneTop + screenH - navH;
        int y = phoneTop + statusH + PAD_Y;

        // ---- 标题 ----
        g.drawString(font, Component.translatable("mcphone.settings.font_color").getString(),
                x, y, FontPalette.title(), true);
        y += font.lineHeight + 4;

        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 4;

        // ---- 预设列表 ----
        final FontPreset[] presets = FontPreset.values();
        final FontPreset current = FontPalette.current();
        final int rowH = Math.max(font.lineHeight, SWATCH_H) + 6;

        int hovered = -1;

        for (int i = 0; i < presets.length; i++) {
            if (y + rowH > bottom) break;

            FontPreset preset = presets[i];
            boolean isCurrent = preset == current;

            if (GuiUtil.hit(mouseX, mouseY, x, y, w, rowH)) {
                hovered = i;
                g.fill(x, y, x + w, y + rowH, PhoneTheme.COLOR_ROW_HOVER);
            }

            drawSwatch(g, preset, x + 2, y + (rowH - SWATCH_H) / 2);

            String label = Component.translatable(preset.translationKey()).getString();
            g.drawString(font, label,
                    x + 2 + SWATCH_W + SWATCH_GAP, y + (rowH - font.lineHeight) / 2,
                    isCurrent ? FontPalette.link() : FontPalette.body(), false);

            if (isCurrent) {
                g.drawString(font, CURRENT_MARK,
                        x + w - font.width(CURRENT_MARK) - 2, y + (rowH - font.lineHeight) / 2,
                        FontPalette.link(), false);
            }

            y += rowH;
        }

        this.hoveredIdx = hovered;
    }

    /**
     * 画一个预设的色块：从最显眼渐变到最不显眼，外加一圈边。
     *
     * 为什么是渐变而不是几块颜色
     *
     * 1.6.15 之前这里画三块离散色块（明显度 10 / 5 / 0）。三块颜色摆在
     * 一起，读起来就是"这一类里有三个颜色可以选"—— 而它们其实是同一条
     * 渐变上的三个采样点，点一下选中的是【整条】。这个误读是界面自己
     * 造成的，不是玩家想歪了。
     *
     * 逐列画，每一列取一档明显度，画出来就是一条渐变，一眼看得出是
     * "从亮到暗"而不是"三选一"。取的值与界面真正在用的是同一套
     * （都走 FontPalette.sample），所以色块承诺什么、屏幕上就是什么。
     *
     * 那一圈边不是装饰。BLACK 预设最亮的一档是纯黑，画在深色壁纸上时
     * 色块与背景连成一片，看着像"这一格没画出来"。
     */
    private static void drawSwatch(GuiGraphics g, FontPreset preset, int x, int y) {
        for (int i = 0; i < SWATCH_W; i++) {
            // 四舍五入而不是直接截断：截断会让最右一列取不到明显度 0，
            // 渐变的暗端就差一档
            int prominence = MAX_PROMINENCE
                    - (i * MAX_PROMINENCE + SWATCH_W / 2) / SWATCH_W;
            g.fill(x + i, y, x + i + 1, y + SWATCH_H,
                    FontPalette.sample(preset, prominence));
        }

        g.renderOutline(x - 1, y - 1, SWATCH_W + 2, SWATCH_H + 2, PhoneTheme.COLOR_DIVIDER);
    }

    //  点击

    /** 返回 true 表示选中了某个预设，界面该退回设置列表 */
    public boolean mouseClicked(int button) {
        if (button != 0 || hoveredIdx < 0) return false;

        FontPreset[] presets = FontPreset.values();
        if (hoveredIdx >= presets.length) return false;

        ClientConfig.selectFontColor(presets[hoveredIdx]);
        return true;
    }

    /** 离开这一页：hover 不留到下次进来 */
    public void close() {
        hoveredIdx = -1;
    }
}
