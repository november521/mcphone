package com.november.mcphone.feature.gallery.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.ImageCodec;
import com.november.mcphone.core.client.ImageFolder;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 照片网格的画法与尺寸 —— 相册与"选一张发给好友"那一页共用。
 *
 * 为什么单独一份
 *
 * 这两页要的东西一模一样：一格一格的缩略图、等比塞进格子、悬停高亮、底下一条
 * 「◁ 3/12 ▷」。不同的只有点下去之后干什么——相册是打开单张查看，美西螈是把它发出去。
 * 那点不同不值得复制一份网格代码：格子多宽、几列、缓存要顶到多大，都是"改一处必须
 * 同时改另一处"的数，而这种成对的数迟早会对不上。
 *
 * 每页几行由调用方按自己的可用高度算（两页的头尾不一样高），列数与格子尺寸在这里定死。
 */
public final class PhotoGridPainter {

    private PhotoGridPainter() {}

    /** 每行几张 */
    public static final int COLS = 3;

    /** 缩略图格子尺寸。3 列 33 宽 + 2 道 4 间隙 = 107，正好落在 108 的内容宽里 */
    public static final int CELL_W = 33;
    public static final int CELL_H = 24;

    /** 格子间距 */
    public static final int GAP = 4;

    /** 翻页箭头热区宽度。比字形大一圈——手机屏幕上字太小不好点 */
    public static final int ARROW_HIT_W = 16;

    public static final String ARROW_PREV = "◁";
    public static final String ARROW_NEXT = "▷";

    private static final int COLOR_CELL_BG    = PhoneTheme.COLOR_SCRIM;
    private static final int COLOR_CELL_HOVER = PhoneTheme.COLOR_HOVER_STRONG;

    private static int colorAccent() { return FontPalette.link(); }
    private static int colorPager() { return FontPalette.body(); }
    private static int colorPagerOff() { return FontPalette.muted(); }
    private static int colorHint() { return FontPalette.subtle(); }

    /** 一整个网格有多宽，用来在内容区里居中 */
    public static int gridWidth() {
        return COLS * CELL_W + (COLS - 1) * GAP;
    }

    /** 给定高度放得下几行，至少一行 */
    public static int rowsFor(int availableHeight) {
        return Math.max(1, (availableHeight + GAP) / (CELL_H + GAP));
    }

    /** 第 slot 格（页内序号，从 0 起）的左上角 x */
    public static int cellX(int gridX, int slot) {
        return gridX + (slot % COLS) * (CELL_W + GAP);
    }

    /** 第 slot 格的左上角 y */
    public static int cellY(int gridTop, int slot) {
        return gridTop + (slot / COLS) * (CELL_H + GAP);
    }

    public static boolean cellHit(int cx, int cy, int mouseX, int mouseY) {
        return mouseX >= cx && mouseX < cx + CELL_W && mouseY >= cy && mouseY < cy + CELL_H;
    }

    /**
     * 画一个缩略图格子：底色 + 等比居中的图 + 悬停高亮。图还没加载好就画一个占位点。
     *
     * 收 {@link ImageFolder} 而不是写死相册：表情那一页画的是同一种格子，只是目录不同。
     */
    public static void cell(GuiGraphics g, Font font, ImageFolder folder, ImageFolder.Entry entry,
                            int cx, int cy, boolean hovered) {

        g.fill(cx, cy, cx + CELL_W, cy + CELL_H, COLOR_CELL_BG);

        ImageCodec.Texture thumb = folder.thumbnail(entry);
        if (thumb == null) {
            // 还在后台加载（或加载失败），画个占位点，下一帧再问
            String dots = "…";
            g.drawString(font, dots,
                    cx + (CELL_W - font.width(dots)) / 2,
                    cy + (CELL_H - font.lineHeight) / 2,
                    colorHint(), false);
        } else {
            // 留 1px 内边距，免得贴着边框
            GuiUtil.drawFitted(g, thumb, cx + 1, cy + 1, CELL_W - 2, CELL_H - 2);
        }

        if (hovered) {
            g.fill(cx, cy, cx + CELL_W, cy + CELL_H, COLOR_CELL_HOVER);
            drawBorder(g, cx, cy, CELL_W, CELL_H, colorAccent());
        }
    }

    public static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /**
     * 底部翻页条：◁ 当前/总页 ▷。返回鼠标停在哪个箭头上：-1 上一页，1 下一页，0 都不是。
     *
     * 两个箭头都贴内容区边缘，与标题、总数同一列。往里缩几像素看着"整齐"，实际是让
     * 这一行比上下几行都窄一圈——◁ ▷ 在 unifont 里是 8 点宽的半角字，本身没有边距要补。
     */
    public static int pager(GuiGraphics g, Font font, int x, int y, int w, int h,
                            int cur, int pageCount, int mouseX, int mouseY) {

        boolean canPrev = cur > 0;
        boolean canNext = cur < pageCount - 1;

        boolean onPrev = mouseX >= x && mouseX < x + ARROW_HIT_W
                      && mouseY >= y && mouseY < y + h;
        boolean onNext = mouseX >= x + w - ARROW_HIT_W && mouseX < x + w
                      && mouseY >= y && mouseY < y + h;

        int ty = y + (h - font.lineHeight) / 2;

        g.drawString(font, ARROW_PREV, x, ty,
                canPrev ? (onPrev ? colorAccent() : colorPager()) : colorPagerOff(), false);

        String label = (cur + 1) + "/" + pageCount;
        g.drawString(font, label, x + (w - font.width(label)) / 2, ty, colorPager(), false);

        g.drawString(font, ARROW_NEXT, x + w - font.width(ARROW_NEXT), ty,
                canNext ? (onNext ? colorAccent() : colorPager()) : colorPagerOff(), false);

        return (onPrev && canPrev) ? -1 : (onNext && canNext) ? 1 : 0;
    }
}
