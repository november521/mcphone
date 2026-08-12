package com.november.mcphone.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 相册界面 —— 由 PhoneScreen 嵌入渲染。
 *
 * 缩略图网格 + 分页。照片数据与贴图缓存全在 {@link PhotoLibrary}，
 * 本类只负责摆放与交互。
 *
 * ================================================================
 * 为什么是分页而不是滚动
 * ================================================================
 *
 * 滚动列表会让"当前可见的是哪几张"随时在变，而缩略图是按需加载、
 * 缓存还有上限的（见 PhotoLibrary），滑动过程中会不断触发加载与逐出。
 * 分页则每页是一组固定的照片，一页 15 张正好落在缓存容量内，
 * 停在某页时不会有任何多余加载。
 *
 * 照片可能上千张，因此翻页给了四种方式：点箭头、滚轮、左右方向键、
 * Home/End 跳到首尾页。
 */
public final class Gallery {

    // ==================== 布局 ====================

    private static final int PAD = 6;

    /** 每行缩略图数量 */
    private static final int COLS = 3;

    /** 缩略图格子尺寸。3 列 33 宽 + 2 道 4 间隙 = 107，正好落在 108 的内容宽里 */
    private static final int CELL_W = 33;
    private static final int CELL_H = 24;

    /** 格子间距 */
    private static final int GAP = 4;

    /** 翻页箭头热区宽度。比字形大一圈——手机屏幕上字太小不好点 */
    private static final int ARROW_HIT_W = 16;

    // ==================== 颜色 ====================

    private static final int COLOR_CELL_BG      = 0x66000000;
    private static final int COLOR_CELL_HOVER   = 0x44FFFFFF;
    private static final int COLOR_CELL_BORDER  = 0xFF88CCFF;
    private static final int COLOR_PAGER        = 0xFFCCCCCC;
    private static final int COLOR_PAGER_OFF    = 0xFF555555;
    private static final int COLOR_HINT         = 0xFF888888;

    private static final String ARROW_PREV = "◁";
    private static final String ARROW_NEXT = "▷";

    // ==================== 状态 ====================

    /** 当前页，从 0 开始 */
    private int page = 0;

    /** 本帧鼠标悬停的照片下标（全局下标，非页内），-1 为无 */
    private int hoveredIdx = -1;

    /** 本帧鼠标是否悬停在翻页箭头上：-1 上一页，1 下一页，0 都不是 */
    private int hoveredPager = 0;

    /** 上一帧算出的每页容量，供点击与翻页复用 */
    private int perPage = COLS * 5;

    // ============================================================
    //  进入 / 离开
    // ============================================================

    /** 进入相册时调用：重扫目录，这样刚拍的照片立刻可见 */
    public void open() {
        PhotoLibrary.refresh();
        page = 0;
        hoveredIdx = -1;
        hoveredPager = 0;
    }

    /**
     * 离开相册时调用：释放全部缩略图贴图。
     * 照片贴图对手机的其他界面毫无用处，留着白占显存。
     */
    public void close() {
        PhotoLibrary.releaseAll();
    }

    // ============================================================
    //  渲染
    // ============================================================

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        final List<PhotoLibrary.Photo> photos = PhotoLibrary.getPhotos();

        int x = phoneLeft + PAD;
        int y = phoneTop + statusH + 4;
        int w = screenW - PAD * 2;

        // ---- 标题：带照片总数 ----
        String title = Component.translatable("mcphone.app.gallery").getString();
        g.drawString(font, title, x, y, PhoneTheme.FONT_COLOR_TITLE, true);
        if (!photos.isEmpty()) {
            String count = String.valueOf(photos.size());
            g.drawString(font, count, x + w - font.width(count), y, COLOR_HINT, false);
        }
        y += font.lineHeight + 4;

        g.fill(x, y, x + w, y + 1, 0x44FFFFFF);
        y += 4;

        // ---- 空相册 ----
        if (photos.isEmpty()) {
            g.drawString(font, Component.translatable("mcphone.gallery.empty").getString(),
                    x, y, COLOR_HINT, false);
            g.drawString(font, Component.translatable("mcphone.gallery.empty_hint").getString(),
                    x, y + font.lineHeight + 2, COLOR_HINT, false);
            hoveredIdx = -1;
            hoveredPager = 0;
            return;
        }

        // ---- 网格区域 ----
        final int pagerH = font.lineHeight + 4;
        final int gridTop = y;
        final int gridBottom = phoneTop + screenH - navH - pagerH - 2;

        // 行数按可用高度算，改主题尺寸时不必回来改这里
        int rows = Math.max(1, (gridBottom - gridTop + GAP) / (CELL_H + GAP));
        this.perPage = COLS * rows;

        // 照片可能被删掉或换了目录，页码越界时拉回最后一页
        int pageCount = Math.max(1, (photos.size() + perPage - 1) / perPage);
        if (page >= pageCount) page = pageCount - 1;
        if (page < 0) page = 0;

        // 整个网格在内容区里居中
        int gridW = COLS * CELL_W + (COLS - 1) * GAP;
        int gridX = x + (w - gridW) / 2;

        hoveredIdx = -1;
        int first = page * perPage;
        int last = Math.min(first + perPage, photos.size());

        for (int i = first; i < last; i++) {
            int slot = i - first;
            int cx = gridX + (slot % COLS) * (CELL_W + GAP);
            int cy = gridTop + (slot / COLS) * (CELL_H + GAP);

            boolean hovered = mouseX >= cx && mouseX < cx + CELL_W
                           && mouseY >= cy && mouseY < cy + CELL_H;
            if (hovered) hoveredIdx = i;

            renderCell(g, font, photos.get(i), cx, cy, hovered);
        }

        renderPager(g, font, x, gridBottom + 2, w, pagerH, page, pageCount, mouseX, mouseY);
    }

    /** 画一个缩略图格子：底色 + 等比居中的图 + 悬停高亮 */
    private void renderCell(GuiGraphics g, Font font, PhotoLibrary.Photo photo,
                            int cx, int cy, boolean hovered) {

        g.fill(cx, cy, cx + CELL_W, cy + CELL_H, COLOR_CELL_BG);

        PhotoLibrary.Thumb thumb = PhotoLibrary.thumbnail(photo);
        if (thumb == null) {
            // 还在后台加载（或加载失败），画个占位点，下一帧再问
            String dots = "…";
            g.drawString(font, dots,
                    cx + (CELL_W - font.width(dots)) / 2,
                    cy + (CELL_H - font.lineHeight) / 2,
                    COLOR_HINT, false);
        } else {
            // 等比缩放塞进格子，留 1px 内边距免得贴着边框
            int boxW = CELL_W - 2;
            int boxH = CELL_H - 2;
            float scale = Math.min((float) boxW / thumb.width(), (float) boxH / thumb.height());
            int dw = Math.max(1, Math.round(thumb.width() * scale));
            int dh = Math.max(1, Math.round(thumb.height() * scale));
            int dx = cx + (CELL_W - dw) / 2;
            int dy = cy + (CELL_H - dh) / 2;

            // 11 参重载：目标宽高在前、UV 在后，源区取满整张纹理
            g.blit(thumb.texture(), dx, dy, dw, dh, 0, 0,
                    thumb.width(), thumb.height(), thumb.width(), thumb.height());
        }

        if (hovered) {
            g.fill(cx, cy, cx + CELL_W, cy + CELL_H, COLOR_CELL_HOVER);
            drawBorder(g, cx, cy, CELL_W, CELL_H, COLOR_CELL_BORDER);
        }
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** 底部翻页条：◁ 当前/总页 ▷ */
    private void renderPager(GuiGraphics g, Font font, int x, int y, int w, int h,
                             int cur, int pageCount, int mouseX, int mouseY) {

        boolean canPrev = cur > 0;
        boolean canNext = cur < pageCount - 1;

        boolean onPrev = mouseX >= x && mouseX < x + ARROW_HIT_W
                      && mouseY >= y && mouseY < y + h;
        boolean onNext = mouseX >= x + w - ARROW_HIT_W && mouseX < x + w
                      && mouseY >= y && mouseY < y + h;

        hoveredPager = (onPrev && canPrev) ? -1 : (onNext && canNext) ? 1 : 0;

        int ty = y + (h - font.lineHeight) / 2;

        g.drawString(font, ARROW_PREV, x + 2, ty,
                canPrev ? (onPrev ? COLOR_CELL_BORDER : COLOR_PAGER) : COLOR_PAGER_OFF, false);

        String label = (cur + 1) + "/" + pageCount;
        g.drawString(font, label, x + (w - font.width(label)) / 2, ty, COLOR_PAGER, false);

        g.drawString(font, ARROW_NEXT, x + w - font.width(ARROW_NEXT) - 2, ty,
                canNext ? (onNext ? COLOR_CELL_BORDER : COLOR_PAGER) : COLOR_PAGER_OFF, false);
    }

    // ============================================================
    //  交互
    // ============================================================

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        if (hoveredPager < 0) { flip(-1); return true; }
        if (hoveredPager > 0) { flip(1); return true; }

        // 点中缩略图：查看单张照片将在后续实现
        return hoveredIdx >= 0;
    }

    /** 滚轮翻页：向下滚翻到下一页 */
    public boolean mouseScrolled(double scrollY) {
        if (scrollY == 0) return false;
        flip(scrollY < 0 ? 1 : -1);
        return true;
    }

    /**
     * 方向键翻页，Home/End 跳首尾页。
     * 照片上千张时，只能一页页点箭头会很难受。
     */
    public boolean keyPressed(int keyCode) {
        switch (keyCode) {
            case 262 -> { flip(1);  return true; }   // →
            case 263 -> { flip(-1); return true; }   // ←
            case 264 -> { flip(1);  return true; }   // ↓
            case 265 -> { flip(-1); return true; }   // ↑
            case 268 -> { page = 0; return true; }   // Home
            case 269 -> {                            // End
                page = Math.max(0, lastPage());
                return true;
            }
            default -> { return false; }
        }
    }

    private void flip(int delta) {
        int target = page + delta;
        // 到头就停住，不循环——不然一直滚轮会在首尾之间反复跳
        page = Math.max(0, Math.min(target, lastPage()));
    }

    private int lastPage() {
        int n = PhotoLibrary.count();
        if (n == 0 || perPage <= 0) return 0;
        return (n + perPage - 1) / perPage - 1;
    }
}
