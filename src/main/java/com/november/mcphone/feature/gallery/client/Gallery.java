package com.november.mcphone.feature.gallery.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.ImageCodec;
import com.november.mcphone.core.client.ImageFolder;
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

    /**
     * 网格的列数、格子尺寸、间距、翻页条都搬到 {@link PhotoGridPainter} 了 —— 美西螈
     * 「选一张发给好友」那一页画的是同一种网格，这些数只该有一份。
     *
     * 这里留的别名（连同下面两个箭头字符）是给【单张查看】用的：那一页的返回与左右切换
     * 用的是同样的箭头与热区宽度，看着一致才不别扭，但它与网格的翻页不是一回事。
     */
    private static final int ARROW_HIT_W = PhotoGridPainter.ARROW_HIT_W;

    /**
     * 文字按钮的命中区四边各放宽多少。
     *
     * 正好按字的边界算会点不中：这一行字才 9 像素高，而手机整体是缩放显示的。
     * 四边一样宽，不然命中区与看到的那行字对不上，玩家会觉得"这个键有点偏"。
     */
    private static final int HIT_PAD = 2;

    /** 删除键的贴图边长。与音乐页那几个键一样大，它们是同一家的 */
    private static final int BTN = 9;

    // ==================== 颜色 ====================

    private static int colorCellBorder() { return FontPalette.link(); }
    private static int colorPager() { return FontPalette.body(); }
    private static int colorPagerOff() { return FontPalette.muted(); }
    private static int colorHint() { return FontPalette.subtle(); }

    private static final String ARROW_PREV = PhotoGridPainter.ARROW_PREV;
    private static final String ARROW_NEXT = PhotoGridPainter.ARROW_NEXT;

    // ==================== 状态 ====================

    /** 当前页，从 0 开始 */
    private int page = 0;

    /** 本帧鼠标悬停的照片下标（全局下标，非页内），-1 为无 */
    private int hoveredIdx = -1;

    /** 本帧鼠标是否悬停在翻页箭头上：-1 上一页，1 下一页，0 都不是 */
    private int hoveredPager = 0;

    /** 上一帧算出的每页容量，供点击与翻页复用 */
    private int perPage = PhotoGridPainter.COLS * 5;

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

    //  进入 / 离开

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

    //  渲染

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        final List<ImageFolder.Entry> photos = PhotoLibrary.getPhotos();

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
        this.perPage = PhotoGridPainter.COLS * PhotoGridPainter.rowsFor(gridBottom - gridTop);

        // 缓存必须装得下一整页，否则同页内先加载的会被后加载的挤掉，
        // 下一帧又重新加载，画面持续闪烁。行数随手机屏幕高度变，
        // 所以每帧把上限顶到位而不是写死
        PhotoLibrary.ensureCacheFor(perPage);

        // 照片可能被删掉或换了目录，页码越界时拉回最后一页
        int pageCount = Math.max(1, (photos.size() + perPage - 1) / perPage);
        if (page >= pageCount) page = pageCount - 1;
        if (page < 0) page = 0;

        // 整个网格在内容区里居中
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

            PhotoGridPainter.cell(g, font, PhotoLibrary.folder(), photos.get(i), cx, cy, hovered);
        }

        hoveredPager = PhotoGridPainter.pager(g, font, x, gridBottom + 2, w, pagerH,
                page, pageCount, mouseX, mouseY);
    }

    //  单张查看

    /**
     * 单张查看：顶部「◁ 返回 … 删除」，中间大图，底部「◁ 3/47 ▷」。
     *
     * 删除为什么在右上角，序号为什么在正中
     *
     * 1.7.56 之前正好反过来：序号占着右上角，删除夹在底部两个翻页箭头【中间】。
     * 那是两个错。
     *
     * 翻照片是要连点箭头的，而删除是这一页唯一一个不可撤销的动作——把它放在
     * 两个连点目标的正中间，等于专挑最容易点错的位置摆最不该点错的键。虽然
     * 有"再点一次确认"兜底，但兜底是给手滑准备的，不是给布局的错误开脱的。
     *
     * 序号则相反：它只是个读数，点不点它都没事，占着最好按的那个角是浪费。
     * 挪到底部正中之后，这一行就成了 {@code ◁ 3/47 ▷}，与网格页的翻页条
     * （见 {@link PhotoGridPainter#pager}）一模一样——同一个位置在两页里含义相同，
     * 不再是"网格里是页码、单张里是删除"。
     *
     * 大图未加载完时先拿缩略图放大顶着——虽然糊，但翻看时不会闪空白，
     * 大图就绪的那一帧自然换上。
     */
    private void renderViewer(GuiGraphics g, int phoneLeft, int phoneTop,
                              int screenW, int screenH, int statusH, int navH,
                              int mouseX, int mouseY, Font font,
                              List<ImageFolder.Entry> photos) {

        ImageFolder.Entry photo = photos.get(viewing);

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;
        final int rowH = font.lineHeight + 2;

        // ---- 顶部：返回 + 序号 ----
        // 与网格那一页的标题行同一个基线（都是 statusH + 4）。原先这里是 +2，
        // 于是点开一张照片时顶上那一行会往上跳 2 像素
        int headerY = phoneTop + statusH + 4;

        String back = ARROW_PREV + " " + Component.translatable("mcphone.gallery.back").getString();

        // 命中区四边各放 HIT_PAD。原先是"左 0 右 4、上 0 下 2"——按到的地方
        // 与看到的那行字对不上
        boolean onBack = mouseX >= x - HIT_PAD && mouseX < x + font.width(back) + HIT_PAD
                      && mouseY >= headerY - HIT_PAD
                      && mouseY < headerY + font.lineHeight + HIT_PAD;

        g.drawString(font, back, x, headerY, onBack ? colorCellBorder() : colorPager(), false);

        // ---- 底部两行：文件名 / ◁ 序号 ▷ ----
        int btnRowY = phoneTop + screenH - navH - rowH - 2;
        int nameRowY = btnRowY - rowH;

        boolean canPrev = viewing > 0;
        boolean canNext = viewing < photos.size() - 1;

        boolean onPrev = mouseX >= x && mouseX < x + ARROW_HIT_W
                      && mouseY >= btnRowY && mouseY < btnRowY + rowH;
        boolean onNext = mouseX >= x + w - ARROW_HIT_W && mouseX < x + w
                      && mouseY >= btnRowY && mouseY < btnRowY + rowH;

        // 删除键：贴图优先（垃圾桶），缺图画「删除」两个字。上膛之后一律是
        // 文字 —— 理由见 PhoneSkin.Element.GALLERY_DELETE。
        // 两支的宽度不一样，命中区得在画之前算，所以先问一句有没有图
        boolean delIcon = !deleteArmed && PhoneSkin.has(PhoneSkin.Element.GALLERY_DELETE);
        String del = Component.translatable(
                deleteArmed ? "mcphone.gallery.delete_confirm" : "mcphone.gallery.delete").getString();

        // 上膛之后那句话比「删除」长得多，而它与左边的返回同在一行。留 6 像素缝
        // 之后放不下就截断：宁可显示成「再点一次确认…」，也不能糊到返回上面去
        // ——那两个键一个是退出、一个是删除，叠在一起点错的代价太大
        int delMaxW = w - font.width(back) - 6;
        if (!delIcon) del = GuiUtil.truncate(font, del, delMaxW);

        int delW = delIcon ? BTN : font.width(del);
        int delX = x + w - delW;
        int delY = delIcon ? headerY + (font.lineHeight - BTN) / 2 : headerY;
        boolean onDelete = mouseX >= delX - HIT_PAD && mouseX < delX + delW + HIT_PAD
                        && mouseY >= headerY - HIT_PAD
                        && mouseY < headerY + font.lineHeight + HIT_PAD;

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

        // 与顶部那个返回的 ◁ 同一列，理由见 PhotoGridPainter.pager
        g.drawString(font, ARROW_PREV, x, btnRowY,
                canPrev ? (onPrev ? colorCellBorder() : colorPager()) : colorPagerOff(), false);
        g.drawString(font, ARROW_NEXT, x + w - font.width(ARROW_NEXT), btnRowY,
                canNext ? (onNext ? colorCellBorder() : colorPager()) : colorPagerOff(), false);

        // 序号摆在两个箭头正中，与网格页的翻页条同一个形状
        String idx = (viewing + 1) + "/" + photos.size();
        g.drawString(font, idx, x + (w - font.width(idx)) / 2, btnRowY, colorPager(), false);

        // ---- 右上角：删除 ----
        if (delIcon) {
            // 悬停铺一层"危险"色的底，与 App 管理器里卸载那一行同一块色
            // —— 文字那一支是变红，图标不能变色，就让底变
            if (onDelete) {
                g.fill(delX - HIT_PAD, delY - HIT_PAD, delX + BTN + HIT_PAD, delY + BTN + HIT_PAD,
                        PhoneTheme.COLOR_ROW_HOVER_DANGER);
            }
            PhoneSkin.draw(g, PhoneSkin.Element.GALLERY_DELETE, delX, delY, BTN, BTN);
        } else {
            g.drawString(font, del, delX, delY,
                    deleteArmed ? FontPalette.dangerArmed()
                            : (onDelete ? FontPalette.danger() : colorPager()), false);
        }
    }

    /** 大图区域：黑底 + 等比居中的照片 */
    private void renderPhoto(GuiGraphics g, Font font, ImageFolder.Entry photo,
                             int areaX, int areaY, int areaW, int areaH) {

        // 黑底：照片可能是任意比例，留白处不该透出壁纸
        g.fill(areaX, areaY, areaX + areaW, areaY + areaH, PhoneTheme.COLOR_OVERLAY);

        ImageCodec.Texture img = PhotoLibrary.preview(photo);
        if (img == null) img = PhotoLibrary.thumbnail(photo);   // 大图未就绪，先用缩略图顶着

        if (img == null) {
            String loading = Component.translatable("mcphone.gallery.loading").getString();
            g.drawString(font, loading,
                    areaX + (areaW - font.width(loading)) / 2,
                    areaY + (areaH - font.lineHeight) / 2, colorHint(), false);
            return;
        }

        GuiUtil.drawFitted(g, img, areaX + 2, areaY + 2, areaW - 4, areaH - 4);
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

    //  交互

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

        ImageFolder.Entry photo = PhotoLibrary.get(viewing);
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
