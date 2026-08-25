package com.november.mcphone.feature.camera.client;

import com.november.mcphone.core.client.MCphoneKeyBindings;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 相机取景框覆盖层。全部 g.fill() 代码绘制、尺寸相对屏幕算，任意分辨率与宽高比下观感一致。
 * TODO: 贴图替换接口——玩家提供取景框贴图时改用贴图绘制。
 */
public final class CameraOverlay {

    /** 四角卡尺的臂长，占屏幕短边的比例 */
    private static final float BRACKET_LEN_RATIO = 0.06f;
    /** 卡尺线宽，像素 */
    private static final int BRACKET_THICKNESS = 2;
    /** 卡尺距屏幕边缘的留白，占屏幕短边的比例 */
    private static final float BRACKET_MARGIN_RATIO = 0.04f;
    private static final int COLOR_BRACKET = PhoneTheme.COLOR_VIEWFINDER;

    /** 中心准星臂长，像素 */
    private static final int RETICLE_ARM = 5;
    private static final int COLOR_RETICLE = PhoneTheme.COLOR_RETICLE;

    /** 提示完整显示时长，毫秒 */
    private static final int HINT_HOLD_MS = 4000;
    /** 提示淡出时长，毫秒 */
    private static final int HINT_FADE_MS = 1200;
    private static final int COLOR_HINT = 0xFFFFFF;

    /** 拍照白闪时长，毫秒 */
    private static final int FLASH_MS = 220;

    private CameraOverlay() {}

    public static void render(GuiGraphics g, Font font, int w, int h, long nowMs) {
        renderViewfinder(g, w, h);
        renderHint(g, font, w, h, nowMs);
        renderFlash(g, w, h, nowMs);
    }

    private static void renderViewfinder(GuiGraphics g, int w, int h) {
        int shortSide = Math.min(w, h);
        int len = Math.max(8, (int) (shortSide * BRACKET_LEN_RATIO));
        int margin = Math.max(4, (int) (shortSide * BRACKET_MARGIN_RATIO));
        int t = BRACKET_THICKNESS;

        int l = margin, r = w - margin, top = margin, bot = h - margin;

        g.fill(l, top, l + len, top + t, COLOR_BRACKET);
        g.fill(l, top, l + t, top + len, COLOR_BRACKET);
        g.fill(r - len, top, r, top + t, COLOR_BRACKET);
        g.fill(r - t, top, r, top + len, COLOR_BRACKET);
        g.fill(l, bot - t, l + len, bot, COLOR_BRACKET);
        g.fill(l, bot - len, l + t, bot, COLOR_BRACKET);
        g.fill(r - len, bot - t, r, bot, COLOR_BRACKET);
        g.fill(r - t, bot - len, r, bot, COLOR_BRACKET);

        int cx = w / 2, cy = h / 2;
        g.fill(cx - RETICLE_ARM, cy, cx - 1, cy + 1, COLOR_RETICLE);
        g.fill(cx + 2, cy, cx + RETICLE_ARM + 1, cy + 1, COLOR_RETICLE);
        g.fill(cx, cy - RETICLE_ARM, cx + 1, cy - 1, COLOR_RETICLE);
        g.fill(cx, cy + 2, cx + 1, cy + RETICLE_ARM + 1, COLOR_RETICLE);
    }

    private static void renderHint(GuiGraphics g, Font font, int w, int h, long nowMs) {
        long elapsed = nowMs - CameraMode.getEnteredAtMs();
        if (elapsed > HINT_HOLD_MS + HINT_FADE_MS) return;

        float alpha = elapsed <= HINT_HOLD_MS
                ? 1.0f
                : 1.0f - (float) (elapsed - HINT_HOLD_MS) / HINT_FADE_MS;
        alpha = Mth.clamp(alpha, 0f, 1f);
        if (alpha <= 0.01f) return;

        // 按键名取自玩家的实际绑定，改键后提示自动跟着变
        Component hint = Component.translatable("mcphone.camera.hint",
                MCphoneKeyBindings.CAMERA_SHUTTER.getTranslatedKeyMessage(),
                MCphoneKeyBindings.CAMERA_EXIT.getTranslatedKeyMessage());

        String text = hint.getString();
        int tw = font.width(text);
        int x = (w - tw) / 2;
        int y = h - Math.max(24, h / 8);

        int a = (int) (alpha * 255) << 24;

        g.fill(x - 4, y - 3, x + tw + 4, y + font.lineHeight + 2, (int) (alpha * 0x88) << 24);
        g.drawString(font, text, x, y, a | COLOR_HINT, false);
    }

    private static void renderFlash(GuiGraphics g, int w, int h, long nowMs) {
        long since = nowMs - CameraMode.getFlashAtMs();
        if (since < 0 || since > FLASH_MS) return;

        float alpha = 1.0f - (float) since / FLASH_MS;
        int a = (int) (Mth.clamp(alpha, 0f, 1f) * 200) << 24;
        g.fill(0, 0, w, h, a | 0xFFFFFF);
    }
}
