package com.november.mcphone.gui;

import com.november.mcphone.network.NetworkHandler;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 手机机身的绘制 —— 外壳、壁纸、状态栏、导航栏。
 *
 * 抽成静态方法是因为不止一个界面要画手机：{@link PhoneScreen} 是普通
 * Screen，而带格子的界面必须继承原版的 AbstractContainerScreen 才能
 * 白拿物品交换逻辑，两者没有共同基类可放这些代码。壁纸那段等比裁剪
 * 尤其不能抄两遍——它的 blit 参数顺序踩过坑（见下方注释）。
 *
 * 所有坐标参数都是"屏幕内区域左上角"，即不含边框。边框由本类自己往
 * 外扩，调用方不必关心。
 */
public final class PhoneChassis {

    private PhoneChassis() {}

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 画外壳与屏幕背景（壁纸或纯色），尺寸为标准竖屏机身。
     *
     * @param phoneLeft 屏幕内区域左上角 X（不含边框）
     * @param phoneTop  屏幕内区域左上角 Y（不含边框）
     */
    public static void drawFrameAndWallpaper(GuiGraphics g, int phoneLeft, int phoneTop) {
        drawFrameAndWallpaper(g, phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT);
    }

    /**
     * 画任意尺寸的外壳与屏幕背景。
     *
     * 容器类界面（末影箱等）不受竖屏机身尺寸约束：格子要 9 列 ×18px，
     * 120px 宽的机身塞不下，硬塞就得牺牲功能。这类界面自己定尺寸，
     * 但仍复用同一套外壳与壁纸绘制，视觉上还是同一部手机。
     *
     * @param screenW 屏幕内区域宽（不含边框）
     * @param screenH 屏幕内区域高（不含边框）
     */
    public static void drawFrameAndWallpaper(GuiGraphics g, int phoneLeft, int phoneTop,
                                             int screenW, int screenH) {
        final int fl = phoneLeft - PhoneTheme.PHONE_BORDER;
        final int ft = phoneTop - PhoneTheme.PHONE_BORDER;
        final int fw = screenW + PhoneTheme.PHONE_BORDER * 2;
        final int fh = screenH + PhoneTheme.PHONE_BORDER * 2;

        g.fill(fl, ft, fl + fw, ft + fh, PhoneTheme.COLOR_FRAME);
        g.fill(fl, ft, fl + fw, ft + 2, PhoneTheme.COLOR_FRAME_HIGHLIGHT);

        String wpName = NetworkHandler.WakeholderData.get();
        WallpaperStore.WallpaperEntry wp = WallpaperStore.findEntry(wpName);

        if (wp != null) {
            // 按 cover 方式等比缩放：覆盖整个屏幕，超出部分居中裁剪
            int texW = wp.imageWidth();
            int texH = wp.imageHeight();
            float sw = (float) screenW / texW;
            float sh = (float) screenH / texH;
            float s = Math.max(sw, sh);
            int srcW = (int) (screenW / s);
            int srcH = (int) (screenH / s);
            int srcX = (texW - srcW) / 2;
            int srcY = (texH - srcH) / 2;

            // 参数顺序按 GuiGraphics 的 11 参重载：
            //   (贴图, x, y, 目标宽, 目标高, u, v, 源区宽, 源区高, 纹理宽, 纹理高)
            // 目标宽高在前、UV 在后，写反会导致目标矩形取到 srcX/srcY，
            // 而居中裁剪下二者必有一个为 0，壁纸就整个画不出来
            g.blit(wp.texture(),
                    phoneLeft, phoneTop,
                    screenW, screenH,
                    srcX, srcY,
                    srcW, srcH,
                    texW, texH);
        } else {
            g.fill(phoneLeft, phoneTop,
                    phoneLeft + screenW,
                    phoneTop + screenH,
                    PhoneTheme.COLOR_SCREEN_BG);
        }
    }

    /**
     * 画顶部状态栏：左侧信号、右侧时钟，中间可选文字。
     *
     * @param centerText 中间显示的文字，null 表示不显示
     */
    public static void drawStatusBar(GuiGraphics g, Font font, int phoneLeft, int phoneTop,
                                     String centerText) {
        g.fill(phoneLeft, phoneTop,
                phoneLeft + PhoneTheme.PHONE_WIDTH,
                phoneTop + PhoneTheme.STATUS_BAR_HEIGHT, 0x66000000);

        String time = LocalTime.now().format(TIME_FORMATTER);
        int tx = phoneLeft + PhoneTheme.PHONE_WIDTH - 6 - font.width(time);
        g.drawString(font, time, tx, phoneTop + 1, PhoneTheme.FONT_COLOR_STATUS, true);
        g.drawString(font, "●●●●", phoneLeft + 4, phoneTop + 1, 0xFFFFFFFF, true);

        if (centerText != null) {
            g.drawString(font, centerText, phoneLeft + PhoneTheme.PHONE_WIDTH / 2 - 12,
                    phoneTop + 1, 0xFF88CCFF, true);
        }
    }

    /** 画底部导航栏：三个虚拟按键 */
    public static void drawNavBar(GuiGraphics g, Font font, int phoneLeft, int phoneTop) {
        int ny = phoneTop + PhoneTheme.PHONE_HEIGHT - PhoneTheme.NAV_BAR_HEIGHT;
        g.fill(phoneLeft, ny, phoneLeft + PhoneTheme.PHONE_WIDTH,
                phoneTop + PhoneTheme.PHONE_HEIGHT, PhoneTheme.COLOR_NAV_BAR);

        int cy = ny + PhoneTheme.NAV_BAR_HEIGHT / 2 - font.lineHeight / 2;
        int tw = PhoneTheme.PHONE_WIDTH / 3;
        String[] btns = {"◁", "○", "□"};
        for (int i = 0; i < btns.length; i++) {
            int bw = font.width(btns[i]);
            int bx = phoneLeft + tw * i + (tw - bw) / 2;
            g.drawString(font, btns[i], bx, cy, 0xFF888888, false);
        }
    }
}
