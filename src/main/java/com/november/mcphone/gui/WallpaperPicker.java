package com.november.mcphone.gui;

import com.november.mcphone.network.NetworkHandler;
import com.november.mcphone.network.SetWallpaperPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 壁纸选择器 —— 在手机屏幕区域内展示壁纸缩略图列表。
 *
 * 渲染被嵌入到 PhoneScreen 的屏幕区域中。
 * 每张壁纸按比例缩放为缩略图展示。
 */
public final class WallpaperPicker {

    // ---- 布局 ----
    private static final int THUMB_W = 46;   // 缩略图宽度
    private static final int THUMB_H = 46;   // 缩略图高度（正方形预览框）
    private static final int GAP = 4;
    private static final int PAD_X = 6;
    private static final int PAD_Y = 2;
    private static final int COLS = 2;

    private int hoveredIdx = -1;     // -2 = "默认"按钮, -1 = 无hover, 0..N = 壁纸索引

    public WallpaperPicker() {}

    // ============================================================
    //  渲染
    // ============================================================

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, net.minecraft.client.gui.Font font) {

        List<WallpaperStore.WallpaperEntry> wallpapers = WallpaperStore.getWallpapers();

        int contentX = phoneLeft + PAD_X;
        int contentY = phoneTop + statusH + PAD_Y;
        int contentBottom = phoneTop + screenH - navH;
        int contentW = screenW - PAD_X * 2;

        // ---- 标题 ----
        g.drawString(font, "更换壁纸", contentX, contentY, PhoneTheme.FONT_COLOR_TITLE, true);
        contentY += font.lineHeight + 4;

        int hovered = -1;

        // ---- "恢复默认" 按钮 ----
        int btnY = contentY;
        if (isHover(mouseX, mouseY, contentX, btnY, contentW, font.lineHeight + 4)) {
            hovered = -2;
            g.fill(contentX, btnY, contentX + contentW, btnY + font.lineHeight + 4, 0x44FFFFFF);
        }
        g.drawString(font, "恢复默认背景", contentX + 2, btnY + 2, 0xFFCCCCCC, false);
        contentY = btnY + font.lineHeight + 6;

        // ---- 分割线 ----
        g.fill(contentX, contentY, contentX + contentW, contentY + 1, 0x44FFFFFF);
        contentY += 4;

        // ---- 无壁纸提示 ----
        if (wallpapers.isEmpty()) {
            g.drawString(font, "暂无壁纸", contentX, contentY, 0xFF888888, false);
            g.drawString(font, "放入PNG到", contentX, contentY + font.lineHeight + 2, 0xFF888888, false);
            g.drawString(font, "config/mcphone/", contentX, contentY + (font.lineHeight + 2) * 2, 0xFF888888, false);
            g.drawString(font, "wallpapers/", contentX, contentY + (font.lineHeight + 2) * 3, 0xFF888888, false);
            this.hoveredIdx = -1;
            return;
        }

        // ---- 壁纸缩略图网格 ----
        int x = contentX;
        int y = contentY;
        int col = 0;

        for (int i = 0; i < wallpapers.size(); i++) {
            WallpaperStore.WallpaperEntry wp = wallpapers.get(i);

            if (y + THUMB_H + font.lineHeight + 2 > contentBottom) break;

            // hover 高亮
            if (isHover(mouseX, mouseY, x, y, THUMB_W, THUMB_H + font.lineHeight + 2)) {
                hovered = i;
                g.fill(x - 1, y - 1, x + THUMB_W + 1, y + THUMB_H + font.lineHeight + 3, 0x4488CCFF);
            }

            // 按比例缩放绘制壁纸纹理
            renderThumbnail(g, wp.texture(), wp.imageWidth(), wp.imageHeight(), x, y, THUMB_W, THUMB_H);

            // 文件名
            String label = wp.displayName();
            if (font.width(label) > THUMB_W) {
                label = font.plainSubstrByWidth(label, THUMB_W - 2) + "…";
            }
            g.drawCenteredString(font, label,
                    x + THUMB_W / 2, y + THUMB_H + 1, 0xFFAAAAAA);

            col++;
            if (col >= COLS) {
                x = contentX;
                y += THUMB_H + font.lineHeight + 4 + 2;
                col = 0;
            } else {
                x += THUMB_W + GAP;
            }
        }

        this.hoveredIdx = hovered;
    }

    // ============================================================
    //  缩略图：按比例缩放居中绘制到预览框内
    // ============================================================

    /**
     * 将任意尺寸纹理等比例缩放到 boxW×boxH 区域内居中绘制。
     * 这是壁纸"能显示任意尺寸PNG"的核心 —— 不写死 blit 尺寸，
     * 而是根据实际宽高比计算目标矩形。
     */
    private static void renderThumbnail(GuiGraphics g, ResourceLocation tex,
                                        int texW, int texH,
                                        int boxX, int boxY, int boxW, int boxH) {
        // 计算等比缩放后的目标尺寸
        float scale = Math.min((float) boxW / texW, (float) boxH / texH);
        int drawW = (int)(texW * scale);
        int drawH = (int)(texH * scale);

        // 居中偏移
        int drawX = boxX + (boxW - drawW) / 2;
        int drawY = boxY + (boxH - drawH) / 2;

        // 绘制 —— 纹理尺寸就是图片原始尺寸
        g.blit(tex, drawX, drawY, 0, 0, drawW, drawH, texW, texH);
    }

    // ============================================================
    //  点击
    // ============================================================

    /**
     * 返回 true 表示选择了壁纸（界面应返回设置列表），false 表示点击在空白处。
     */
    public boolean mouseClicked(int button) {
        if (button != 0) return false;

        if (hoveredIdx == -2) {
            // "恢复默认背景"
            PacketDistributor.sendToServer(new SetWallpaperPacket(""));
            return true;
        }

        if (hoveredIdx >= 0) {
            WallpaperStore.WallpaperEntry wp = WallpaperStore.getWallpaper(hoveredIdx);
            if (wp != null) {
                PacketDistributor.sendToServer(new SetWallpaperPacket(wp.fileName()));
                return true;
            }
        }

        return false;
    }

    private static boolean isHover(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
