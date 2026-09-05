package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.feature.gallery.client.PhotoGridPainter;
import com.november.mcphone.feature.gallery.client.PhotoLibrary;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 「选一张照片发给他」那一页：相册的网格，点一下就发。
 *
 * 为什么不直接复用相册那一页
 *
 * 相册是"看自己的照片"：点开是单张查看，还带一个不可撤销的删除键。这一页是"挑一张"，
 * 点一下就该发出去——同一个手势在两页里必须是两件事，硬合成一页就要多一个模式开关，
 * 而那个开关会渗进相册的每一处交互。
 *
 * 真正该共用的是网格本身（格子多大、几列、怎么翻页），那部分在 {@link PhotoGridPainter}，
 * 照片的扫描与缩略图缓存在 {@link PhotoLibrary}，两边用的是同一份。
 *
 * 选中之后本页只是把路径交出去（{@link #consumeSelection()}），发不发、发给谁由
 * PhoneScreen 决定——它才知道刚才是从哪个会话点进来的。
 */
public final class ChatPhotoPicker {

    private static final int PAD = 6;

    private int page;

    /** 上一帧算出的每页容量，供点击与翻页复用 */
    private int perPage = PhotoGridPainter.COLS * 4;

    /** 本帧鼠标悬停的照片下标（全局下标，非页内），-1 为无 */
    private int hoveredIdx = -1;

    /** 本帧鼠标是否悬停在翻页箭头上：-1 上一页，1 下一页，0 都不是 */
    private int hoveredPager;

    /** 玩家选中的那张，等 PhoneScreen 取走 */
    private Path selected;

    /** 进来时重扫目录，这样刚拍的照片立刻可见 */
    public void open() {
        PhotoLibrary.refresh();
        page = 0;
        hoveredIdx = -1;
        hoveredPager = 0;
        selected = null;
    }

    /** 离开时释放缩略图贴图，与相册同一个理由：这些贴图对别的界面毫无用处 */
    public void close() {
        PhotoLibrary.releaseAll();
        hoveredIdx = -1;
        hoveredPager = 0;
    }

    /** 取走"选了这张"，没有则返回 null */
    public Path consumeSelection() {
        Path out = selected;
        selected = null;
        return out;
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        final List<PhotoLibrary.Photo> photos = PhotoLibrary.getPhotos();

        int x = phoneLeft + PAD;
        int y = phoneTop + statusH + 4;
        int w = screenW - PAD * 2;

        g.drawString(font, Component.translatable("mcphone.chat.pick_photo").getString(),
                x, y, FontPalette.title(), true);
        if (!photos.isEmpty()) {
            String count = String.valueOf(photos.size());
            g.drawString(font, count, x + w - font.width(count), y, FontPalette.subtle(), false);
        }
        y += font.lineHeight + 4;

        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 4;

        if (photos.isEmpty()) {
            hoveredIdx = -1;
            hoveredPager = 0;
            for (var line : font.split(Component.translatable("mcphone.chat.pick_photo_empty"), w)) {
                g.drawString(font, line, x, y, FontPalette.subtle(), false);
                y += font.lineHeight;
            }
            return;
        }

        final int pagerH = font.lineHeight + 4;
        final int gridTop = y;
        final int gridBottom = phoneTop + screenH - navH - pagerH - 2;

        perPage = PhotoGridPainter.COLS * PhotoGridPainter.rowsFor(gridBottom - gridTop);

        // 缓存必须装得下一整页，否则同页内先加载的会被后加载的挤掉，下一帧又重新加载，画面持续闪烁
        PhotoLibrary.ensureCacheFor(perPage);

        int pageCount = Math.max(1, (photos.size() + perPage - 1) / perPage);
        page = Math.clamp(page, 0, pageCount - 1);

        int gridX = x + (w - PhotoGridPainter.gridWidth()) / 2;

        hoveredIdx = -1;
        int first = page * perPage;
        int last = Math.min(first + perPage, photos.size());

        for (int i = first; i < last; i++) {
            int slot = i - first;
            int cx = PhotoGridPainter.cellX(gridX, slot);
            int cy = PhotoGridPainter.cellY(gridTop, slot);

            boolean hovered = PhotoGridPainter.cellHit(cx, cy, mouseX, mouseY);
            if (hovered) hoveredIdx = i;

            PhotoGridPainter.cell(g, font, photos.get(i), cx, cy, hovered);
        }

        hoveredPager = PhotoGridPainter.pager(g, font, x, gridBottom + 2, w, pagerH,
                page, pageCount, mouseX, mouseY);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        if (hoveredPager < 0) { flip(-1); return true; }
        if (hoveredPager > 0) { flip(1); return true; }

        if (hoveredIdx >= 0) {
            PhotoLibrary.Photo photo = PhotoLibrary.get(hoveredIdx);
            if (photo != null) selected = photo.path();
            return true;
        }
        return false;
    }

    /** 滚轮翻页，与相册一致 */
    public boolean mouseScrolled(double scrollY) {
        if (scrollY == 0) return false;
        flip(scrollY < 0 ? 1 : -1);
        return true;
    }

    /** 方向键翻页，Home/End 跳首尾页——照片上千张时只能一页页点箭头会很难受 */
    public boolean keyPressed(int keyCode) {
        switch (keyCode) {
            case 262, 264 -> { flip(1); return true; }    // → ↓
            case 263, 265 -> { flip(-1); return true; }   // ← ↑
            case 268 -> { page = 0; return true; }        // Home
            case 269 -> { page = lastPage(); return true; }
            default -> { return false; }
        }
    }

    private void flip(int delta) {
        // 到头就停住，不循环——不然一直滚轮会在首尾之间反复跳
        page = Math.clamp(page + delta, 0, lastPage());
    }

    private int lastPage() {
        int n = PhotoLibrary.count();
        if (n == 0 || perPage <= 0) return 0;
        return (n + perPage - 1) / perPage - 1;
    }
}
