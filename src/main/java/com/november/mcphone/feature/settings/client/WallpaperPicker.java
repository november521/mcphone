package com.november.mcphone.feature.settings.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.feature.settings.net.SetWallpaperPacket;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import com.november.mcphone.core.net.MCphoneNetwork;

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

    //  渲染

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, net.minecraft.client.gui.Font font) {

        List<WallpaperStore.WallpaperEntry> wallpapers = WallpaperStore.getWallpapers();

        int contentX = phoneLeft + PAD_X;
        int contentY = phoneTop + statusH + PAD_Y;
        int contentBottom = phoneTop + screenH - navH;
        int contentW = screenW - PAD_X * 2;

        // ---- 标题 ----
        g.drawString(font, Component.translatable("mcphone.gui.wallpaper_title").getString(),
                contentX, contentY, FontPalette.title(), true);
        contentY += font.lineHeight + 4;

        int hovered = -1;

        // ---- "恢复默认" 按钮 ----
        int btnY = contentY;
        if (GuiUtil.hit(mouseX, mouseY, contentX, btnY, contentW, font.lineHeight + 4)) {
            hovered = -2;
            g.fill(contentX, btnY, contentX + contentW, btnY + font.lineHeight + 4, PhoneTheme.COLOR_HOVER_STRONG);
        }
        g.drawString(font, Component.translatable("mcphone.gui.wallpaper_default").getString(),
                contentX + 2, btnY + 2, FontPalette.body(), false);
        contentY = btnY + font.lineHeight + 6;

        // ---- 分割线 ----
        g.fill(contentX, contentY, contentX + contentW, contentY + 1, PhoneTheme.COLOR_DIVIDER);
        contentY += 4;

        // ---- 无壁纸提示 ----
        if (wallpapers.isEmpty()) {
            g.drawString(font, Component.translatable("mcphone.gui.wallpaper_empty").getString(),
                    contentX, contentY, FontPalette.subtle(), false);
            g.drawString(font, Component.translatable("mcphone.gui.wallpaper_hint1").getString(),
                    contentX, contentY + font.lineHeight + 2, FontPalette.subtle(), false);
            g.drawString(font, Component.translatable("mcphone.gui.wallpaper_hint2").getString(),
                    contentX, contentY + (font.lineHeight + 2) * 2, FontPalette.subtle(), false);
            g.drawString(font, Component.translatable("mcphone.gui.wallpaper_hint3").getString(),
                    contentX, contentY + (font.lineHeight + 2) * 3, FontPalette.subtle(), false);
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
            if (GuiUtil.hit(mouseX, mouseY, x, y, THUMB_W, THUMB_H + font.lineHeight + 2)) {
                hovered = i;
                g.fill(x - 1, y - 1, x + THUMB_W + 1, y + THUMB_H + font.lineHeight + 3, PhoneTheme.COLOR_SELECTION);
            }

            // 按比例缩放绘制壁纸纹理
            renderThumbnail(g, wp.texture(), wp.imageWidth(), wp.imageHeight(), x, y, THUMB_W, THUMB_H);

            // 文件名
            String label = wp.displayName();
            if (font.width(label) > THUMB_W) {
                label = font.plainSubstrByWidth(label, THUMB_W - 2) + "…";
            }
            g.drawCenteredString(font, label,
                    x + THUMB_W / 2, y + THUMB_H + 1, FontPalette.appName());

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

    //  缩略图：按比例缩放居中绘制到预览框内

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

        // 必须用带"源区宽高"的 11 参重载：9 参那个不缩放，
        // 它是从贴图左上角取 drawW×drawH 一块按 1:1 画出来，
        // 结果是原图左上角的一小块裁切而非缩略图。
        // 这里源区取满整张纹理(texW×texH)，缩放进 drawW×drawH 才是等比预览。
        GuiUtil.drawTexture(g, tex, drawX, drawY,
                drawW, drawH,    // 目标宽高
                texW, texH);     // 纹理总宽高，源区取满整张
    }

    //  点击

    /**
     * 返回 true 表示选择了壁纸（界面应返回设置列表），false 表示点击在空白处。
     */
    public boolean mouseClicked(int button) {
        if (button != 0) return false;

        if (hoveredIdx == -2) {
            // "恢复默认背景"
            MCphoneNetwork.sendToServer(new SetWallpaperPacket(""));
            return true;
        }

        if (hoveredIdx >= 0) {
            WallpaperStore.WallpaperEntry wp = WallpaperStore.getWallpaper(hoveredIdx);
            if (wp != null) {
                MCphoneNetwork.sendToServer(new SetWallpaperPacket(wp.fileName()));
                return true;
            }
        }

        return false;
    }

}
