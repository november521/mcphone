package com.november.mcphone.feature.settings.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.feature.settings.net.SetWallpaperPacket;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * 壁纸选择器 —— 在手机屏幕区域内展示壁纸缩略图列表。
 *
 * 渲染被嵌入到 PhoneScreen 的屏幕区域中。
 * 每张壁纸按比例缩放为缩略图展示。
 *
 * 网格按行滚（1.9.1 补的）。在那之前一屏只画得下两行四张，第五张起就是永远看不见 ——
 * 而这个目录是玩家自己往里丢文件的地方，丢满是迟早的事。见 {@link #scrollRow}。
 */
public final class WallpaperPicker {

    // ---- 布局 ----
    private static final int THUMB_W = 46;   // 缩略图宽度
    private static final int THUMB_H = 46;   // 缩略图高度（正方形预览框）
    private static final int GAP = 4;
    private static final int PAD_X = 6;
    private static final int PAD_Y = 2;
    private static final int COLS = 2;

    /** 标题与右上角那个键之间至少留的空隙 */
    private static final int HEADER_GAP = 4;

    /** 右上角那个键的点击判定往外放宽一点，字太小不好点 */
    private static final int HIT_PAD = 2;

    private int hoveredIdx = -1;     // -3 = "打开文件夹", -2 = "恢复默认", -1 = 无hover, 0..N = 壁纸索引

    /**
     * 网格从第几【行】开始画。
     *
     * 这一页原来没有滚动，也没有翻页：一屏两列两行，第五张之后的壁纸就是永远看不见。
     * 而壁纸目录是玩家自己往里丢文件的地方，丢第五张进去是迟早的事——他会以为
     * 那张图没被认出来，转头去报"壁纸加载不出来"。
     */
    private int scrollRow;

    /** 上一帧算出来的滚动上限，给 mouseScrolled 夹用 —— 一屏放得下几行只有渲染时才知道 */
    private int maxScrollRow;

    /**
     * 点过「打开文件夹」之后开始盯着目录，每秒重扫一次。
     *
     * 不这么做的话，这个键只完成了一半：玩家点开文件夹、拖一张 PNG 进去、切回游戏——
     * 而这一页只在【进来的时候】扫过一次，那张图要退出去再进来才认。他多半会以为没放成功。
     *
     * 只在点过之后才盯：那是玩家说出"我要往里放东西"的唯一时刻。没点过的人不该为此
     * 每秒付一次目录列举。
     */
    private boolean watchingFolder;

    private long lastScanMs;

    /** 目录重扫的间隔。列一次目录的开销可以忽略，真正贵的加载只发生在有新文件时 */
    private static final long RESCAN_INTERVAL_MS = 1000L;

    public WallpaperPicker() {}

    /** 每次进入这一页时调，见 PhoneScreen 的 navigateTo */
    public void open() {
        hoveredIdx = -1;
        watchingFolder = false;
        lastScanMs = 0L;
        scrollRow = 0;
    }

    //  渲染

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, net.minecraft.client.gui.Font font) {

        if (watchingFolder) {
            long now = System.currentTimeMillis();
            if (now - lastScanMs >= RESCAN_INTERVAL_MS) {
                lastScanMs = now;
                WallpaperStore.refresh();   // 增量的：没有新文件时它什么都不做
            }
        }

        List<WallpaperStore.WallpaperEntry> wallpapers = WallpaperStore.getWallpapers();

        int contentX = phoneLeft + PAD_X;
        int contentY = phoneTop + statusH + PAD_Y;
        int contentBottom = phoneTop + screenH - navH;
        int contentW = screenW - PAD_X * 2;

        int hovered = -1;

        // ---- 标题行：左边标题，右边「打开文件夹」----
        //
        // 挂在标题行而不是自己占一行：多占一行正好把网格从两行挤成一行，一屏能看到的
        // 壁纸从四张掉到两张。1.9.1 给网格补上滚轮之后这不再是"看不见"，但每滚一下
        // 只换两张仍然难挑——为了一个快捷键把主功能的密度砍掉一半，不划算。
        //
        // 挤不下时截的是标题：玩家正是点着「更换壁纸」那一行进来的，标题只是复述一遍；
        // 而这个键是这一页唯一的新功能。中文两样都放得下，英文的标题会被截一截。
        String open = Component.translatable("mcphone.gui.open_folder").getString();
        int openW = font.width(open);
        int openX = contentX + contentW - openW;
        if (GuiUtil.hit(mouseX, mouseY, openX - HIT_PAD, contentY - HIT_PAD,
                openW + HIT_PAD * 2, font.lineHeight + HIT_PAD * 2)) {
            hovered = -3;
        }
        g.drawString(font, open, openX, contentY,
                hovered == -3 ? FontPalette.title() : FontPalette.link(), false);

        String title = GuiUtil.truncate(font,
                Component.translatable("mcphone.gui.wallpaper_title").getString(),
                openX - contentX - HEADER_GAP);
        g.drawString(font, title, contentX, contentY, FontPalette.title(), true);
        contentY += font.lineHeight + 4;

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
        //
        // 一格占 cellH，最后一行不需要底下那点行距，所以判可见性用 cellNeed
        final int cellNeed = THUMB_H + font.lineHeight + 2;
        final int cellH = THUMB_H + font.lineHeight + 4 + 2;

        final int availH = contentBottom - contentY;
        final int visibleRows = availH < cellNeed ? 1 : (availH - cellNeed) / cellH + 1;
        final int totalRows = (wallpapers.size() + COLS - 1) / COLS;
        // 删掉几张图之后行数会变少，不夹一下就会停在空白处
        maxScrollRow = Math.max(0, totalRows - visibleRows);
        scrollRow = Mth.clamp(scrollRow, 0, maxScrollRow);

        int x = contentX;
        int y = contentY;
        int col = 0;

        for (int i = scrollRow * COLS; i < wallpapers.size(); i++) {
            WallpaperStore.WallpaperEntry wp = wallpapers.get(i);

            if (y + cellNeed > contentBottom) break;

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

    /** 滚轮翻网格，一次一行。到头了返回 false */
    public boolean mouseScrolled(double scrollY) {
        if (scrollY > 0 && scrollRow > 0) {
            scrollRow--;
            return true;
        }
        if (scrollY < 0 && scrollRow < maxScrollRow) {
            scrollRow++;
            return true;
        }
        return false;
    }

    /**
     * 返回 true 表示选择了壁纸（界面应返回设置列表），false 表示点击在空白处。
     */
    public boolean mouseClicked(int button) {
        if (button != 0) return false;

        if (hoveredIdx == -3) {
            // 交给系统自己的文件管理器，不弹任何 Java 的窗口——AWT 的选择器在 macOS 上
            // 要与游戏抢主线程。开完【留在这一页】：玩家接下来要做的是拖一张图进去再回来，
            // 把他踢回设置列表等于让他再点两下进来
            Util.getPlatform().openPath(WallpaperStore.directory());
            watchingFolder = true;
            return false;
        }

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
