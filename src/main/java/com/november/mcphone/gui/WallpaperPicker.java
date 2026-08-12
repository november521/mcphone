package com.november.mcphone.gui;

import com.november.mcphone.network.NetworkHandler;
import com.november.mcphone.network.SetWallpaperPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 壁纸选择界面 —— 在手机"屏幕"区域显示壁纸缩略图列表。
 *
 * 渲染在 PhoneScreen 的屏幕区域内，不作为独立 Screen。
 * 通过 PhoneScreen 嵌入渲染。
 */
public final class WallpaperPicker {

    private static final int THUMB_WIDTH = 40;
    private static final int THUMB_HEIGHT = 66; // 保持 120:200 的比例，即 3:5
    private static final int SPACING = 6;
    private static final int PADDING_LEFT = 8;
    private static final int PADDING_TOP = 4;
    private static final int ITEMS_PER_ROW = 3;

    /** 保留空白条目表示使用纯色背景 */
    private static final String DISPLAY_NO_WALLPAPER = "默认(纯色)";

    private int hoveredIndex = -1; // -1 = 无壁纸（纯色）, 0~N = 壁纸列表索引

    public WallpaperPicker() {}

    // ============================================================
    //  渲染（在 PhoneScreen 的屏幕区域内）
    // ============================================================

    /**
     * 渲染壁纸选择列表。
     * @param g       GuiGraphics
     * @param screenLeft  手机屏幕左边界
     * @param screenTop   手机屏幕上边界
     * @param screenW     屏幕内容宽度
     * @param screenH     屏幕内容高度（不含导航栏）
     * @param statusH     状态栏高度
     * @param navH        导航栏高度
     * @param mouseX      鼠标 X 坐标
     * @param mouseY      鼠标 Y 坐标
     * @param font        字体
     */
    public void render(GuiGraphics g, int screenLeft, int screenTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, net.minecraft.client.gui.Font font) {

        List<WallpaperStore.WallpaperEntry> wallpapers = WallpaperStore.getWallpapers();

        // 可用内容区
        int contentX = screenLeft + PADDING_LEFT;
        int contentY = screenTop + statusH + PADDING_TOP;
        int contentBottom = screenTop + screenH - navH;

        // 标题
        g.drawString(font, "选择壁纸", contentX, contentY, PhoneTheme.FONT_COLOR_TITLE, true);
        contentY += font.lineHeight + 4;

        int hovered = -1;

        int x = contentX;
        int y = contentY;
        int col = 0;

        // ---- "无壁纸" 选项 ----
        if (isHovering(contentX, contentY, THUMB_WIDTH, THUMB_HEIGHT, mouseX, mouseY)) {
            hovered = -1;
            g.fill(contentX - 1, contentY - 1, contentX + THUMB_WIDTH + 1, contentY + THUMB_HEIGHT + 1, 0xFF888888);
        }
        // 纯色色块代表默认背景
        g.fill(contentX, contentY, contentX + THUMB_WIDTH, contentY + THUMB_HEIGHT, PhoneTheme.COLOR_SCREEN_BG);
        g.drawCenteredString(font, "默认", contentX + THUMB_WIDTH / 2, contentY + THUMB_HEIGHT + 2, 0xFFAAAAAA);
        x += THUMB_WIDTH + SPACING;
        col = 1;

        // ---- 壁纸列表 ----
        for (int i = 0; i < wallpapers.size(); i++) {
            WallpaperStore.WallpaperEntry wp = wallpapers.get(i);

            if (col >= ITEMS_PER_ROW) {
                x = contentX;
                y += THUMB_HEIGHT + font.lineHeight + 2 + 4;
                col = 0;
            }

            if (y + THUMB_HEIGHT > contentBottom) break; // 超出屏幕，不渲染

            if (isHovering(x, y, THUMB_WIDTH, THUMB_HEIGHT, mouseX, mouseY)) {
                hovered = i;
                g.fill(x - 1, y - 1, x + THUMB_WIDTH + 1, y + THUMB_HEIGHT + 1, 0xFF888888);
            }

            // 绘制壁纸缩略图
            g.blit(wp.texture(), x, y, 0, 0, THUMB_WIDTH, THUMB_HEIGHT, THUMB_WIDTH, THUMB_HEIGHT);

            // 绘制名称
            String name = wp.displayName();
            if (font.width(name) > THUMB_WIDTH) {
                name = font.plainSubstrByWidth(name, THUMB_WIDTH - 2) + "…";
            }
            g.drawCenteredString(font, name, x + THUMB_WIDTH / 2, y + THUMB_HEIGHT + 2, 0xFFAAAAAA);

            x += THUMB_WIDTH + SPACING;
            col++;
        }

        this.hoveredIndex = hovered;
    }

    // ============================================================
    //  点击
    // ============================================================

    /**
     * 鼠标点击时调用。返回 true 表示已处理（点击了某个壁纸），false 表示点击在了外面。
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;

        if (this.hoveredIndex == -1) {
            // 选择了"无壁纸"
            PacketDistributor.sendToServer(new SetWallpaperPacket(""));
            return true;
        }

        if (this.hoveredIndex >= 0) {
            WallpaperStore.WallpaperEntry wp = WallpaperStore.getWallpaper(this.hoveredIndex);
            if (wp != null) {
                PacketDistributor.sendToServer(new SetWallpaperPacket(wp.fileName()));
                return true;
            }
        }

        return false;
    }

    private static boolean isHovering(int x, int y, int w, int h, int mx, int my) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
