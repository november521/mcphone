package com.november.mcphone.feature.gallery.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneTheme;
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
 * 为什么是分页而不是滚动
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

    private static final int COLOR_CELL_BG      = PhoneTheme.COLOR_SCRIM;
    private static final int COLOR_CELL_HOVER   = PhoneTheme.COLOR_HOVER_STRONG;
    private static int colorCellBorder() { return FontPalette.link(); }
    private static int colorPager() { return FontPalette.body(); }
    private static int colorPagerOff() { return FontPalette.muted(); }
    private static int colorHint() { return FontPalette.subtle(); }

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

    // ---- 单张查看 ----

    /** 正在查看的照片下标，-1 表示当前是网格 */
    private int viewing = -1;

    /** 单张查看里鼠标悬停的按钮 */
    private enum ViewBtn { NONE, BACK, PREV, NEXT, DELETE }
    private ViewBtn hoveredBtn = ViewBtn.NONE;

    /**
     * 删除是否已"上膛"。
     *
     * 删照片是直接删磁盘文件、不可撤销，所以要点两次：
     * 第一次把按钮变成"再点一次确认"，第二次才真删。
     * 任何其他操作（翻页、返回、切换照片）都会卸掉。
     */
    private boolean deleteArmed = false;

    // ============================================================
    //  进入 / 离开
    // ============================================================

    /** 进入相册时调用：重扫目录，这样刚拍的照片立刻可见 */
    public void open() {
        PhotoLibrary.refresh();
        page = 0;
        hoveredIdx = -1;
        hoveredPager = 0;
        viewing = -1;
        deleteArmed = false;
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

        // 照片可能在查看期间被删空，或下标越界，此时退回网格
        if (viewing >= photos.size()) viewing = photos.isEmpty() ? -1 : photos.size() - 1;
        if (viewing >= 0) {
            renderViewer(g, phoneLeft, phoneTop, screenW, screenH, statusH, navH,
                    mouseX, mouseY, font, photos);
            return;
        }

        int x = phoneLeft + PAD;
        int y = phoneTop + statusH + 4;
        int w = screenW - PAD * 2;

        // ---- 标题：带照片总数 ----
        String title = Component.translatable("mcphone.app.gallery").getString();
        g.drawString(font, title, x, y, FontPalette.title(), true);
        if (!photos.isEmpty()) {
            String count = String.valueOf(photos.size());
            g.drawString(font, count, x + w - font.width(count), y, colorHint(), false);
        }
        y += font.lineHeight + 4;

        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 4;

        // ---- 空相册 ----
        if (photos.isEmpty()) {
            g.drawString(font, Component.translatable("mcphone.gallery.empty").getString(),
                    x, y, colorHint(), false);
            g.drawString(font, Component.translatable("mcphone.gallery.empty_hint").getString(),
                    x, y + font.lineHeight + 2, colorHint(), false);
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

        // 缓存必须装得下一整页，否则同页内先加载的会被后加载的挤掉，
        // 下一帧又重新加载，画面持续闪烁。行数随手机屏幕高度变，
        // 所以每帧把上限顶到位而不是写死
        PhotoLibrary.ensureCacheFor(perPage);

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
                    colorHint(), false);
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
            drawBorder(g, cx, cy, CELL_W, CELL_H, colorCellBorder());
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
                canPrev ? (onPrev ? colorCellBorder() : colorPager()) : colorPagerOff(), false);

        String label = (cur + 1) + "/" + pageCount;
        g.drawString(font, label, x + (w - font.width(label)) / 2, ty, colorPager(), false);

        g.drawString(font, ARROW_NEXT, x + w - font.width(ARROW_NEXT) - 2, ty,
                canNext ? (onNext ? colorCellBorder() : colorPager()) : colorPagerOff(), false);
    }

    // ============================================================
    //  单张查看
    // ============================================================

    /**
     * 单张查看：顶部返回与序号，中间大图，底部文件名与「◁ 删除 ▷」。
     *
     * 大图未加载完时先拿缩略图放大顶着——虽然糊，但翻看时不会闪空白，
     * 大图就绪的那一帧自然换上。
     */
    private void renderViewer(GuiGraphics g, int phoneLeft, int phoneTop,
                              int screenW, int screenH, int statusH, int navH,
                              int mouseX, int mouseY, Font font,
                              List<PhotoLibrary.Photo> photos) {

        PhotoLibrary.Photo photo = photos.get(viewing);

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;
        final int rowH = font.lineHeight + 2;

        // ---- 顶部：返回 + 序号 ----
        int headerY = phoneTop + statusH + 2;
        String back = ARROW_PREV + " " + Component.translatable("mcphone.gallery.back").getString();
        boolean onBack = mouseX >= x && mouseX < x + font.width(back) + 4
                      && mouseY >= headerY && mouseY < headerY + rowH;
        g.drawString(font, back, x, headerY, onBack ? colorCellBorder() : colorPager(), false);

        String idx = (viewing + 1) + "/" + photos.size();
        g.drawString(font, idx, x + w - font.width(idx), headerY, colorHint(), false);

        // ---- 底部两行：文件名 / ◁ 删除 ▷ ----
        int btnRowY = phoneTop + screenH - navH - rowH - 2;
        int nameRowY = btnRowY - rowH;

        boolean canPrev = viewing > 0;
        boolean canNext = viewing < photos.size() - 1;

        boolean onPrev = mouseX >= x && mouseX < x + ARROW_HIT_W
                      && mouseY >= btnRowY && mouseY < btnRowY + rowH;
        boolean onNext = mouseX >= x + w - ARROW_HIT_W && mouseX < x + w
                      && mouseY >= btnRowY && mouseY < btnRowY + rowH;

        String del = Component.translatable(
                deleteArmed ? "mcphone.gallery.delete_confirm" : "mcphone.gallery.delete").getString();
        int delW = font.width(del);
        int delX = x + (w - delW) / 2;
        boolean onDelete = mouseX >= delX - 2 && mouseX < delX + delW + 2
                        && mouseY >= btnRowY && mouseY < btnRowY + rowH;

        hoveredBtn = onBack ? ViewBtn.BACK
                : (onPrev && canPrev) ? ViewBtn.PREV
                : (onNext && canNext) ? ViewBtn.NEXT
                : onDelete ? ViewBtn.DELETE
                : ViewBtn.NONE;

        // ---- 中间：大图 ----
        int imgTop = headerY + rowH + 2;
        int imgBottom = nameRowY - 2;
        renderPhoto(g, font, photo, phoneLeft, imgTop, screenW, imgBottom - imgTop);

        // 文件名过长就截断，手机屏幕放不下完整的时间戳文件名
        String name = photo.fileName();
        if (font.width(name) > w) name = font.plainSubstrByWidth(name, w - 6) + "…";
        g.drawString(font, name, x + (w - font.width(name)) / 2, nameRowY, colorHint(), false);

        g.drawString(font, ARROW_PREV, x + 2, btnRowY,
                canPrev ? (onPrev ? colorCellBorder() : colorPager()) : colorPagerOff(), false);
        g.drawString(font, ARROW_NEXT, x + w - font.width(ARROW_NEXT) - 2, btnRowY,
                canNext ? (onNext ? colorCellBorder() : colorPager()) : colorPagerOff(), false);
        g.drawString(font, del, delX, btnRowY,
                deleteArmed ? FontPalette.dangerArmed()
                        : (onDelete ? FontPalette.danger() : colorPager()), false);
    }

    /** 大图区域：黑底 + 等比居中的照片 */
    private void renderPhoto(GuiGraphics g, Font font, PhotoLibrary.Photo photo,
                             int areaX, int areaY, int areaW, int areaH) {

        // 黑底：照片可能是任意比例，留白处不该透出壁纸
        g.fill(areaX, areaY, areaX + areaW, areaY + areaH, PhoneTheme.COLOR_OVERLAY);

        PhotoLibrary.Thumb img = PhotoLibrary.preview(photo);
        if (img == null) img = PhotoLibrary.thumbnail(photo);   // 大图未就绪，先用缩略图顶着

        if (img == null) {
            String loading = Component.translatable("mcphone.gallery.loading").getString();
            g.drawString(font, loading,
                    areaX + (areaW - font.width(loading)) / 2,
                    areaY + (areaH - font.lineHeight) / 2, colorHint(), false);
            return;
        }

        int boxW = areaW - 4;
        int boxH = areaH - 4;
        float scale = Math.min((float) boxW / img.width(), (float) boxH / img.height());
        int dw = Math.max(1, Math.round(img.width() * scale));
        int dh = Math.max(1, Math.round(img.height() * scale));
        int dx = areaX + (areaW - dw) / 2;
        int dy = areaY + (areaH - dh) / 2;

        g.blit(img.texture(), dx, dy, dw, dh, 0, 0,
                img.width(), img.height(), img.width(), img.height());
    }

    /** 切换到相邻照片。到头就停住。 */
    private void step(int delta) {
        int n = PhotoLibrary.count();
        if (n == 0) { viewing = -1; return; }
        viewing = Math.max(0, Math.min(viewing + delta, n - 1));
        deleteArmed = false;   // 换了张照片，之前上膛的删除作废
    }

    /** 退出单张查看回到网格。返回 false 表示本来就在网格。 */
    public boolean backToGrid() {
        if (viewing < 0) return false;
        viewing = -1;
        deleteArmed = false;
        // 大图对网格毫无用处，立刻归还显存
        PhotoLibrary.releasePreview();
        return true;
    }

    /** 打开单张查看，并把该照片所在页设为当前页，返回网格时位置对得上 */
    private void openViewer(int index) {
        viewing = index;
        deleteArmed = false;
        if (perPage > 0) page = index / perPage;
    }

    // ============================================================
    //  交互
    // ============================================================

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        if (viewing >= 0) {
            switch (hoveredBtn) {
                case BACK   -> backToGrid();
                case PREV   -> step(-1);
                case NEXT   -> step(1);
                case DELETE -> confirmDelete();
                case NONE   -> deleteArmed = false;   // 点空白处即卸掉上膛的删除
            }
            return true;
        }

        if (hoveredPager < 0) { flip(-1); return true; }
        if (hoveredPager > 0) { flip(1); return true; }

        if (hoveredIdx >= 0) { openViewer(hoveredIdx); return true; }
        return false;
    }

    /**
     * 删除按钮：第一次点上膛，第二次才真删。
     * 删的是磁盘上的文件，不可撤销，所以不能一点就没。
     */
    private void confirmDelete() {
        if (!deleteArmed) { deleteArmed = true; return; }
        deleteArmed = false;

        PhotoLibrary.Photo photo = PhotoLibrary.get(viewing);
        if (photo == null || !PhotoLibrary.delete(photo)) return;

        // 删完停在原下标上——那里已经是下一张照片了，
        // 与手机相册的行为一致；删的是最后一张则回退一格
        int n = PhotoLibrary.count();
        if (n == 0) viewing = -1;
        else if (viewing >= n) viewing = n - 1;
    }

    /** 滚轮：网格里翻页，单张查看里切换照片 */
    public boolean mouseScrolled(double scrollY) {
        if (scrollY == 0) return false;
        int dir = scrollY < 0 ? 1 : -1;
        if (viewing >= 0) step(dir);
        else flip(dir);
        return true;
    }

    /**
     * 网格：方向键翻页，Home/End 跳首尾页——照片上千张时
     * 只能一页页点箭头会很难受。
     * 单张查看：方向键切换照片，Home/End 跳到最新/最旧。
     */
    public boolean keyPressed(int keyCode) {
        boolean inViewer = viewing >= 0;
        switch (keyCode) {
            case 262, 264 -> { if (inViewer) step(1);  else flip(1);  return true; }   // → ↓
            case 263, 265 -> { if (inViewer) step(-1); else flip(-1); return true; }   // ← ↑
            case 268 -> {                                                              // Home
                if (inViewer) { viewing = 0; deleteArmed = false; } else page = 0;
                return true;
            }
            case 269 -> {                                                              // End
                if (inViewer) { viewing = Math.max(0, PhotoLibrary.count() - 1); deleteArmed = false; }
                else page = Math.max(0, lastPage());
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
