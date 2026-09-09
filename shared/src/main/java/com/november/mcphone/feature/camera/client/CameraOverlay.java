package com.november.mcphone.feature.camera.client;

import com.november.mcphone.core.client.MCphoneKeyBindings;
import com.november.mcphone.core.client.PhoneKeys;
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

    private CameraOverlay() {}

    /**
     * 三层的顺序是有讲究的：模糊那一版的闪光【必须最先】，它糊的是这一帧已经画完的
     * 画面（世界，以及印在世界上的坐标戳——那一行属于照片，跟着一起糊才对），卡尺与
     * 准星要留在清楚的一层上；白闪那一版反过来，得盖在最上面，不然取景框浮在白幕上，
     * 看着不像闪了一下。
     */
    public static void render(GuiGraphics g, Font font, int w, int h, long nowMs, float partialTick) {
        CameraFlash.renderBlur(g, partialTick, nowMs);
        renderViewfinder(g, w, h);
        renderHint(g, font, w, h, nowMs);
        CameraFlash.renderWhite(g, w, h, nowMs);
    }

    /**
     * 取景框离屏幕边缘留多少。{@code CameraStamp} 也要这个数——坐标戳贴着同一条线
     * 往里让，看着才像被取景框框住的一行字。留白按屏幕短边算，所以它得是个方法。
     */
    static int margin(int w, int h) {
        return Math.max(4, (int) (Math.min(w, h) * BRACKET_MARGIN_RATIO));
    }

    private static void renderViewfinder(GuiGraphics g, int w, int h) {
        int shortSide = Math.min(w, h);
        int len = Math.max(8, (int) (shortSide * BRACKET_LEN_RATIO));
        int margin = margin(w, h);
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
                PhoneKeys.CAMERA_SHUTTER.translatedName(),
                PhoneKeys.CAMERA_EXIT.translatedName());

        String text = hint.getString();
        int tw = font.width(text);
        int x = (w - tw) / 2;
        int y = h - Math.max(24, h / 8);

        int a = (int) (alpha * 255) << 24;

        g.fill(x - 4, y - 3, x + tw + 4, y + font.lineHeight + 2, (int) (alpha * 0x88) << 24);
        g.drawString(font, text, x, y, a | COLOR_HINT, false);
    }
}
