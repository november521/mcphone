package com.november.mcphone.feature.settings.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneScale;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 「设置 → 界面大小」 —— 手机开出来多大。
 *
 * 为什么这一页不需要预览
 *
 * 拖着条改的时候，整个手机（连同这一页自己）就在跟着变——预览就是它本身。所以这一页
 * 只画一条可拖的进度条、两个加减键和当前的百分比，没有别的东西。
 *
 * 三个入口都留着，因为它们各有各的场合：条是"大概拖到那儿"，加减键是"再多一档"，
 * 而 25% 一档正好让整数倍落得到（GUI 缩放 2 配 150% 就是整 3 倍，字最清楚）。
 *
 * 这一页可以换肤
 *
 * 槽、填充、滑块、加减键的底都走 {@link PhoneSkin}（`settings/slider_track` 那四张），
 * 缺图时才用 {@link PhoneTheme} 里那组颜色。这一组四件刻意起的是通用名字：将来音量条
 * 之类的滑条也用它们，别再各画各的。
 *
 * 窗口放不下时
 *
 * 真正生效的倍数会被窗口夹住（见 {@link PhoneScale#effective}），但配置里的数不改——
 * 玩家把窗口拉大之后应该回到他原本要的大小。夹着的时候这一页写一行"窗口放不下"，
 * 不写的话他会以为是加减键坏了：数字在变，手机不变。
 */
public final class UiScalePage {

    private static final int PAD_X = 6;

    /** 加减键的边长，也是那一行的高度 */
    private static final int BTN = 14;

    /** 进度条高度。比按钮矮一截，看着才像"条"而不是第三个按钮 */
    private static final int BAR_H = 6;

    /** 滑块宽度。4 像素：细了抓不住，粗了在 108 像素宽的条上显得笨重 */
    private static final int KNOB_W = 4;

    /** 加减号那两根杠：长 8、厚 2。在 14 像素的键上两头各留 3 像素，正中 */
    private static final int GLYPH_LEN = 8;
    private static final int GLYPH_THICK = 2;

    /** 拖动时按 5% 对齐：手拖不出 1% 的精度，对齐之后数字不会跳得没规律 */
    private static final int DRAG_SNAP = 5;

    /** 上一帧算出来的几何，点击与拖动时复用 */
    private int rowX, rowW;
    private int minusX, plusX, rowY, barX, barW;
    private int snapY, resetY;

    /** 正拖着那个条 */
    private boolean dragging;

    /** 进入这一页 */
    public void open() {
        dragging = false;
    }

    /** 离开这一页。拖着条被导航栏带走时也要落一次盘，否则这次拖动白拖 */
    public void close() {
        if (!dragging) return;
        dragging = false;
        PhoneScale.commit();
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font,
                       int windowW, int windowH) {

        final int x = phoneLeft + PAD_X;
        final int w = screenW - PAD_X * 2;
        int y = phoneTop + statusH + 2;

        //  标题 + 当前值 
        g.drawString(font, Component.translatable("mcphone.settings.ui_scale").getString(),
                x, y, FontPalette.title(), true);

        String value = PhoneScale.percent() + "%";
        g.drawString(font, value, x + w - font.width(value), y, FontPalette.confirm(), false);
        y += font.lineHeight + 4;

        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 6;

        //  加减键 + 进度条 
        rowY = y;
        rowX = x;
        rowW = w;
        minusX = x;
        plusX = x + w - BTN;
        barX = x + BTN + 4;
        barW = w - (BTN + 4) * 2;

        drawStepButton(g, minusX, rowY, false, PhoneScale.percent() > PhoneScale.MIN_PERCENT,
                mouseX, mouseY);
        drawStepButton(g, plusX, rowY, true, PhoneScale.percent() < PhoneScale.MAX_PERCENT,
                mouseX, mouseY);

        int barY = rowY + (BTN - BAR_H) / 2;
        PhoneSkin.drawOrFill(g, PhoneSkin.Element.SLIDER_TRACK,
                barX, barY, barW, BAR_H, PhoneTheme.COLOR_SLIDER_TRACK);

        float t = (float) (PhoneScale.percent() - PhoneScale.MIN_PERCENT)
                / (PhoneScale.MAX_PERCENT - PhoneScale.MIN_PERCENT);
        int fill = Math.round(barW * t);
        if (fill > 0) {
            PhoneSkin.drawOrFill(g, PhoneSkin.Element.SLIDER_FILL,
                    barX, barY, fill, BAR_H, PhoneTheme.COLOR_SLIDER_FILL);
        }

        // 滑块压在填充的末端，比槽高一圈——拖起来看得见自己抓的是什么
        int knobX = Mth.clamp(barX + fill - KNOB_W / 2, barX, barX + barW - KNOB_W);
        PhoneSkin.drawOrFill(g, PhoneSkin.Element.SLIDER_KNOB,
                knobX, rowY, KNOB_W, BTN, PhoneTheme.COLOR_SLIDER_KNOB);

        y = rowY + BTN + 4;

        //  被窗口夹住时说一声 
        if (PhoneScale.clampedByWindow(windowW, windowH)) {
            int real = Math.round(PhoneScale.fit(windowW, windowH) * 100);
            g.drawString(font, GuiUtil.truncate(font,
                            Component.translatable("mcphone.settings.ui_scale_clamped", real + "%").getString(), w),
                    x, y, FontPalette.notice(), false);
        } else if (PhoneScale.snapEnabled()) {
            // 贴合开着时说清楚一档是多少，否则玩家会觉得加减键"跳得没规律"
            int step = (int) Math.round(PhoneScale.crispStepPercent());
            g.drawString(font, GuiUtil.truncate(font,
                            Component.translatable("mcphone.settings.ui_scale_step", step + "%").getString(), w),
                    x, y, FontPalette.dim(), false);
        } else {
            g.drawString(font, GuiUtil.truncate(font,
                            Component.translatable("mcphone.settings.ui_scale_hint").getString(), w),
                    x, y, FontPalette.dim(), false);
        }
        y += font.lineHeight + 6;

        //  只用清晰的倍数 
        snapY = y;
        boolean snapHovered = GuiUtil.hit(mouseX, mouseY, x, snapY, w, BTN);
        if (snapHovered) g.fill(x, snapY, x + w, snapY + BTN, PhoneTheme.COLOR_ROW_HOVER);

        String snapValue = Component.translatable(PhoneScale.snapEnabled()
                ? "mcphone.gui.on" : "mcphone.gui.off").getString();
        int snapValueW = font.width(snapValue);
        int snapTextY = snapY + (BTN - font.lineHeight) / 2;
        g.drawString(font, snapValue, x + w - snapValueW - 2, snapTextY,
                PhoneScale.snapEnabled() ? FontPalette.confirm() : FontPalette.dim(), false);
        g.drawString(font, GuiUtil.truncate(font,
                        Component.translatable("mcphone.settings.ui_scale_snap").getString(),
                        w - snapValueW - 8),
                x + 2, snapTextY, FontPalette.body(), false);

        y += BTN + 4;

        //  还原默认 
        resetY = y;
        boolean resetHovered = GuiUtil.hit(mouseX, mouseY, x, resetY, w, BTN);
        if (resetHovered) g.fill(x, resetY, x + w, resetY + BTN, PhoneTheme.COLOR_ROW_HOVER);

        String reset = Component.translatable("mcphone.settings.ui_scale_reset",
                PhoneScale.DEFAULT_PERCENT + "%").getString();
        g.drawString(font, GuiUtil.truncate(font, reset, w - 4),
                x + (w - Math.min(font.width(reset), w - 4)) / 2,
                resetY + (BTN - font.lineHeight) / 2,
                PhoneScale.percent() == PhoneScale.DEFAULT_PERCENT
                        ? FontPalette.dim() : FontPalette.body(),
                false);
    }

    /**
     * 一个加减键。底可换肤，悬停时整张提亮——有贴图之后"换个颜色"是看不见的，
     * 那一档只能靠亮度，见 {@link PhoneSkin#drawOrFill(GuiGraphics, PhoneSkin.Element,
     * int, int, int, int, int, boolean)}。
     *
     * 【减号与加号是画出来的，不是字符。】原来写的是 "−"（U+2212）与 "+"（ASCII）两个
     * 字符串，按 {@code (BTN - font.width) / 2} 与 {@code (BTN - lineHeight) / 2} 居中——
     * 而这两个字模在各自行高里的落点根本不一样：减号是一根悬在中线附近的短杠、加号是个
     * 占满字身的十字，横居中算出来也就差一两像素，竖着更是差一整档。两个键并排摆着，
     * 差一像素都看得出来。何况字符还受字体与资源包影响，今天对齐了明天换套字体又歪。
     *
     * 画成两根杠就没有这些事：长度与厚度都从 BTN 推出来，永远正中。
     */
    private void drawStepButton(GuiGraphics g, int x, int y, boolean plus,
                                boolean enabled, int mouseX, int mouseY) {
        boolean hovered = enabled && GuiUtil.hit(mouseX, mouseY, x, y, BTN, BTN);

        PhoneSkin.drawOrFill(g, PhoneSkin.Element.STEP_BUTTON, x, y, BTN, BTN,
                hovered ? PhoneTheme.COLOR_STEP_BUTTON_HOVER : PhoneTheme.COLOR_STEP_BUTTON,
                hovered);

        int color = enabled ? FontPalette.title() : FontPalette.dim();
        int off = (BTN - GLYPH_LEN) / 2;        // 长边两头留的空
        int mid = (BTN - GLYPH_THICK) / 2;      // 短边两头留的空

        g.fill(x + off, y + mid, x + off + GLYPH_LEN, y + mid + GLYPH_THICK, color);
        if (plus) {
            g.fill(x + mid, y + off, x + mid + GLYPH_THICK, y + off + GLYPH_LEN, color);
        }
    }

    /** 点一下。落在条上等于"跳到这儿"并开始拖 */
    public void mouseClicked(double mx, double my) {
        if (GuiUtil.hit(mx, my, minusX, rowY, BTN, BTN)) {
            PhoneScale.nudge(-PhoneScale.STEP_PERCENT);
            return;
        }
        if (GuiUtil.hit(mx, my, plusX, rowY, BTN, BTN)) {
            PhoneScale.nudge(PhoneScale.STEP_PERCENT);
            return;
        }
        if (GuiUtil.hit(mx, my, rowX, snapY, rowW, BTN)) {
            PhoneScale.setSnap(!PhoneScale.snapEnabled());
            return;
        }
        if (GuiUtil.hit(mx, my, rowX, resetY, rowW, BTN)) {
            PhoneScale.setPercent(PhoneScale.DEFAULT_PERCENT);
            return;
        }
        // 条本身放宽一点点命中：它只有 6 像素高，按整行算才点得准
        if (GuiUtil.hit(mx, my, barX, rowY, barW, BTN)) {
            dragging = true;
            applyFromX(mx);
        }
    }

    /** 拖动。只认横坐标——这条只有横向有意义 */
    public boolean mouseDragged(double mx) {
        if (!dragging) return false;
        applyFromX(mx);
        return true;
    }

    /** 松手：把拖出来的那个值落一次盘。拖的过程中一次都不写，见 PhoneScale.preview */
    public void mouseReleased() {
        if (!dragging) return;
        dragging = false;
        PhoneScale.commit();
    }

    private void applyFromX(double mx) {
        if (barW <= 0) return;
        float t = (float) Mth.clamp((mx - barX) / barW, 0.0, 1.0);
        int raw = Math.round(PhoneScale.MIN_PERCENT
                + t * (PhoneScale.MAX_PERCENT - PhoneScale.MIN_PERCENT));
        PhoneScale.preview(Math.round((float) raw / DRAG_SNAP) * DRAG_SNAP);
    }
}
