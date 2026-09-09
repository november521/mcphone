package com.november.mcphone.feature.settings.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.MCphoneKeyBindings;
import com.november.mcphone.core.client.PhoneHudPlacement;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 「设置 → 副手 HUD」 —— 手机放进副手时挂在画面上的那一部。
 *
 * 这一页管三件事：开不开、多大、摆哪儿。
 *
 * 位置为什么不在这儿摆
 *
 * 要摆的是整个窗口里的位置，而这一页只有 120 像素宽。放个缩略图进来拖，一像素的手抖
 * 对应实际画面就是十几像素，摆不准；玩家真正想知道的"会不会挡住物品栏"更是只有按原尺寸
 * 摆在真画面上才看得出来。所以这一行是个入口，点开 {@link PhoneHudEditor}。
 *
 * 大小为什么在这儿
 *
 * 与位置相反：手机多大是自己就能看见的事，而且这一页本身就在那部手机里——
 * 不过要注意，玩家在【这一页】上看到的是全屏那副面孔，它归「界面大小」管，
 * 和这里调的这个数不是一回事。所以这一行右边写着的是数字，不是所见即所得，
 * 底下那句提示就是干这个用的。
 *
 * 滑条与加减键的画法与 {@link UiScalePage} 刻意一致——同一个模组里的两条滑条，
 * 长得不一样只会让人以为它们是两种东西。
 */
public final class HudPage {

    private static final int PAD_X = 6;

    /** 一行的高度，也是加减键的边长。与 UiScalePage 同 */
    private static final int BTN = 14;

    private static final int BAR_H = 6;

    private static final int KNOB_W = 4;

    /** 加减号那两根杠：长 8、厚 2。画出来而不是写字符，理由见 UiScalePage.drawStepButton */
    private static final int GLYPH_LEN = 8;
    private static final int GLYPH_THICK = 2;

    /** 拖动时按 5% 对齐：手拖不出 1% 的精度 */
    private static final int DRAG_SNAP = 5;

    /** 上一帧算出来的几何，点击与拖动时复用 */
    private int rowX, rowW;
    private int toggleY, scaleY, placeY, resetY;
    private int minusX, plusX, barX, barW;

    private boolean dragging;

    /** 玩家点了「位置」，等 PhoneScreen 把编辑器开出来 */
    private boolean editRequested;

    public void open() {
        dragging = false;
        editRequested = false;
    }

    /** 离开这一页。拖着条被导航栏带走时也要落一次盘，否则这次拖动白拖 */
    public void close() {
        if (!dragging) return;
        dragging = false;
        PhoneHudPlacement.commitPercent();
    }

    /** PhoneScreen 每帧问一次：要不要开位置编辑器 */
    public boolean consumeEditRequest() {
        if (!editRequested) return false;
        editRequested = false;
        return true;
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        final int x = phoneLeft + PAD_X;
        final int w = screenW - PAD_X * 2;
        int y = phoneTop + statusH + 2;

        // 「还原默认」钉在内容区底部而不是跟着往下排：上面几段说明是会折行的，换个语言、
        // 换套字体就可能多出一行，跟着流下去迟早会顶进导航栏里。它的上沿也就是流式排版
        // 的底线，中间的说明排到这儿就停，见 drawWrapped
        resetY = phoneTop + screenH - navH - BTN - 2;
        final int bottom = resetY - 2;

        rowX = x;
        rowW = w;

        //  标题 + 开关状态
        g.drawString(font, Component.translatable("mcphone.settings.hud").getString(),
                x, y, FontPalette.title(), true);

        String state = Component.translatable(
                PhoneHudPlacement.enabled() ? "mcphone.gui.on" : "mcphone.gui.off").getString();
        g.drawString(font, state, x + w - font.width(state), y,
                PhoneHudPlacement.enabled() ? FontPalette.confirm() : FontPalette.dim(), false);
        y += font.lineHeight + 4;

        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 6;

        //  开关
        toggleY = y;
        drawRow(g, font, x, w, toggleY, mouseX, mouseY,
                Component.translatable("mcphone.hud.enabled").getString(),
                state, PhoneHudPlacement.enabled() ? FontPalette.confirm() : FontPalette.dim());
        y += BTN + 2;

        //  说明：自动那条怎么算、手动那个键是哪个。两句合成一段而不是各占一段——
        //  这块屏幕只有 120×200，每空一行都是从下面挤出来的
        for (var line : font.split(Component.translatable("mcphone.hud.enabled_hint",
                MCphoneKeyBindings.HUD_TOGGLE.getTranslatedKeyMessage()), w)) {
            if (y + font.lineHeight > bottom) break;
            g.drawString(font, line, x, y, FontPalette.dim(), false);
            y += font.lineHeight;
        }
        y += 4;

        //  大小
        String scaleLabel = Component.translatable("mcphone.hud.scale").getString();
        String scaleValue = PhoneHudPlacement.percent() + "%";
        g.drawString(font, scaleLabel, x, y, FontPalette.body(), false);
        g.drawString(font, scaleValue, x + w - font.width(scaleValue), y,
                FontPalette.confirm(), false);
        y += font.lineHeight + 3;

        scaleY = y;
        minusX = x;
        plusX = x + w - BTN;
        barX = x + BTN + 4;
        barW = w - (BTN + 4) * 2;

        drawStepButton(g, minusX, scaleY, false,
                PhoneHudPlacement.percent() > PhoneHudPlacement.MIN_PERCENT, mouseX, mouseY);
        drawStepButton(g, plusX, scaleY, true,
                PhoneHudPlacement.percent() < PhoneHudPlacement.MAX_PERCENT, mouseX, mouseY);

        int barY = scaleY + (BTN - BAR_H) / 2;
        PhoneSkin.drawOrFill(g, PhoneSkin.Element.SLIDER_TRACK,
                barX, barY, barW, BAR_H, PhoneTheme.COLOR_SLIDER_TRACK);

        float t = (float) (PhoneHudPlacement.percent() - PhoneHudPlacement.MIN_PERCENT)
                / (PhoneHudPlacement.MAX_PERCENT - PhoneHudPlacement.MIN_PERCENT);
        int fill = Math.round(barW * t);
        if (fill > 0) {
            PhoneSkin.drawOrFill(g, PhoneSkin.Element.SLIDER_FILL,
                    barX, barY, fill, BAR_H, PhoneTheme.COLOR_SLIDER_FILL);
        }

        int knobX = Mth.clamp(barX + fill - KNOB_W / 2, barX, barX + barW - KNOB_W);
        PhoneSkin.drawOrFill(g, PhoneSkin.Element.SLIDER_KNOB,
                knobX, scaleY, KNOB_W, BTN, PhoneTheme.COLOR_SLIDER_KNOB);

        y = scaleY + BTN + 2;

        // 这一页上看到的是全屏那副面孔，它归「界面大小」管，与这个数不是一回事。
        // 不说一句的话，玩家会一边拖一边纳闷"怎么没反应"
        y = drawWrapped(g, font, "mcphone.hud.scale_hint", x, y, w, bottom) + 4;

        //  位置
        placeY = y;
        String anchorName = Component.translatable(
                PhoneHudPlacement.anchor().translationKey()).getString();
        drawRow(g, font, x, w, placeY, mouseX, mouseY,
                Component.translatable("mcphone.hud.position").getString(),
                anchorName, FontPalette.body());
        y += BTN + 2;

        //  怎么唤出鼠标、唤出之后能干什么。键名取自玩家的实际绑定，改键后提示自动跟着变
        for (var line : font.split(Component.translatable("mcphone.hud.interact_hint",
                MCphoneKeyBindings.HUD_INTERACT.getTranslatedKeyMessage()), w)) {
            if (y + font.lineHeight > bottom) break;
            g.drawString(font, line, x, y, FontPalette.dim(), false);
            y += font.lineHeight;
        }

        //  还原默认
        boolean resetHovered = GuiUtil.hit(mouseX, mouseY, x, resetY, w, BTN);
        if (resetHovered) g.fill(x, resetY, x + w, resetY + BTN, PhoneTheme.COLOR_ROW_HOVER);

        String reset = Component.translatable("mcphone.hud.reset").getString();
        g.drawString(font, GuiUtil.truncate(font, reset, w - 4),
                x + (w - Math.min(font.width(reset), w - 4)) / 2,
                resetY + (BTN - font.lineHeight) / 2,
                isDefault() ? FontPalette.dim() : FontPalette.body(), false);
    }

    /**
     * 画一段会折行的说明，返回下一行该从哪儿开始。
     *
     * 排到 bottom 就停手。这几段说明的行数是算不准的——换个语言、换套资源包字体，
     * 同一句话就可能多出一行。不设这道闸的话，多出来的那行会直接压在底下那个按钮上。
     */
    private int drawWrapped(GuiGraphics g, Font font, String key, int x, int y, int w, int bottom) {
        for (var line : font.split(Component.translatable(key), w)) {
            if (y + font.lineHeight > bottom) break;
            g.drawString(font, line, x, y, FontPalette.dim(), false);
            y += font.lineHeight;
        }
        return y;
    }

    private static boolean isDefault() {
        return PhoneHudPlacement.anchor() == PhoneHudPlacement.DEFAULT_ANCHOR
                && PhoneHudPlacement.offsetX() == 0
                && PhoneHudPlacement.offsetY() == 0
                && PhoneHudPlacement.percent() == PhoneHudPlacement.DEFAULT_PERCENT;
    }

    /** 左边标签、右边取值的一整行，悬停时整行垫一层。与设置列表里那些行同一个观感 */
    private void drawRow(GuiGraphics g, Font font, int x, int w, int rowY,
                         int mouseX, int mouseY, String label, String value, int valueColor) {
        if (GuiUtil.hit(mouseX, mouseY, x, rowY, w, BTN)) {
            g.fill(x, rowY, x + w, rowY + BTN, PhoneTheme.COLOR_ROW_HOVER);
        }

        int valueW = font.width(value);
        int textY = rowY + (BTN - font.lineHeight) / 2;
        g.drawString(font, value, x + w - valueW - 2, textY, valueColor, false);
        g.drawString(font, GuiUtil.truncate(font, label, w - valueW - 8),
                x + 2, textY, FontPalette.body(), false);
    }

    /** 一个加减键。与 {@link UiScalePage} 那两个是同一副画法，两处一改要一起改 */
    private void drawStepButton(GuiGraphics g, int x, int y, boolean plus,
                                boolean enabled, int mouseX, int mouseY) {
        boolean hovered = enabled && GuiUtil.hit(mouseX, mouseY, x, y, BTN, BTN);

        PhoneSkin.drawOrFill(g, PhoneSkin.Element.STEP_BUTTON, x, y, BTN, BTN,
                hovered ? PhoneTheme.COLOR_STEP_BUTTON_HOVER : PhoneTheme.COLOR_STEP_BUTTON,
                hovered);

        int color = enabled ? FontPalette.title() : FontPalette.dim();
        int off = (BTN - GLYPH_LEN) / 2;
        int mid = (BTN - GLYPH_THICK) / 2;

        g.fill(x + off, y + mid, x + off + GLYPH_LEN, y + mid + GLYPH_THICK, color);
        if (plus) {
            g.fill(x + mid, y + off, x + mid + GLYPH_THICK, y + off + GLYPH_LEN, color);
        }
    }

    //  输入

    public void mouseClicked(double mx, double my) {
        if (GuiUtil.hit(mx, my, rowX, toggleY, rowW, BTN)) {
            PhoneHudPlacement.setEnabled(!PhoneHudPlacement.enabled());
            return;
        }
        if (GuiUtil.hit(mx, my, minusX, scaleY, BTN, BTN)) {
            PhoneHudPlacement.nudgePercent(-PhoneHudPlacement.STEP_PERCENT);
            return;
        }
        if (GuiUtil.hit(mx, my, plusX, scaleY, BTN, BTN)) {
            PhoneHudPlacement.nudgePercent(PhoneHudPlacement.STEP_PERCENT);
            return;
        }
        if (GuiUtil.hit(mx, my, rowX, placeY, rowW, BTN)) {
            editRequested = true;
            return;
        }
        if (GuiUtil.hit(mx, my, rowX, resetY, rowW, BTN)) {
            PhoneHudPlacement.reset();
            return;
        }
        // 条本身放宽到整行高：它只有 6 像素，按 6 像素算点不准
        if (GuiUtil.hit(mx, my, barX, scaleY, barW, BTN)) {
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

    /** 松手：把拖出来的那个值落一次盘。拖的过程中一次都不写 */
    public void mouseReleased() {
        if (!dragging) return;
        dragging = false;
        PhoneHudPlacement.commitPercent();
    }

    private void applyFromX(double mx) {
        if (barW <= 0) return;
        float t = (float) Mth.clamp((mx - barX) / barW, 0.0, 1.0);
        int raw = Math.round(PhoneHudPlacement.MIN_PERCENT
                + t * (PhoneHudPlacement.MAX_PERCENT - PhoneHudPlacement.MIN_PERCENT));
        PhoneHudPlacement.previewPercent(Math.round((float) raw / DRAG_SNAP) * DRAG_SNAP);
    }
}
