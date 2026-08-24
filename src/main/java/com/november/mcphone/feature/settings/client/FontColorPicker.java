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

    /** 色块里那三条分别取哪一档明显度 */
    private static final int[] SWATCH_STEPS = {10, 5, 0};

    /** 当前生效的那一项右侧的标记 */
    private static final String CURRENT_MARK = "✔";

    /** 鼠标停在第几个预设上，-1 表示不在任何一个上 */
    private int hoveredIdx = -1;

    // ============================================================
    //  渲染
    // ============================================================

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
     * 画一个预设的色块：三条竖着排的横带，外加一圈边。
     *
     * 那一圈边不是装饰。BLACK 预设最亮的一档是纯黑，画在深色壁纸上时
     * 色块与背景连成一片，看着像"这一格没画出来"。
     */
    private static void drawSwatch(GuiGraphics g, FontPreset preset, int x, int y) {
        int bandW = SWATCH_W / SWATCH_STEPS.length;

        for (int i = 0; i < SWATCH_STEPS.length; i++) {
            int bx = x + bandW * i;
            // 最后一条补齐除不尽的余数，否则右边会缺一像素
            int bw = (i == SWATCH_STEPS.length - 1) ? SWATCH_W - bandW * i : bandW;
            g.fill(bx, y, bx + bw, y + SWATCH_H, FontPalette.sample(preset, SWATCH_STEPS[i]));
        }

        g.renderOutline(x - 1, y - 1, SWATCH_W + 2, SWATCH_H + 2, PhoneTheme.COLOR_DIVIDER);
    }

    // ============================================================
    //  点击
    // ============================================================

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
