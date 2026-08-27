package com.november.mcphone.core.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.app.IPhoneApp;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 主屏 —— App 图标网格、拖动排序、分页与翻页。
 * 只认手机本地坐标：四个输入方法收到的一律是已撤掉开机缩放的坐标，反变换留在 PhoneScreen。
 * 点开 App 只记进 {@link #consumeLaunchRequest()}，由 PhoneScreen 取走再决定怎么开。
 * 输入方法用的上下文（机身位置、字体、时刻）是上一帧 {@link #render} 带进来的。
 */
public final class HomeGrid {

    private int phoneLeft, phoneTop;
    private int gridStartX, gridStartY;
    private Font font;
    private long nowMs;
    private boolean animationDone;

    /** 鼠标停在第几个 App 上（全局下标），-1 表示没有 */
    private int hoveredAppIndex = -1;

    private int homePage = 0;

    private boolean pressedBlank;

    /** 翻页动画：从哪一页滑过来的，什么时候开始滑 */
    private int slideFromPage;
    private long pageSlideStartMs;

    /** 按下的是第几个 App。到底算点开还是算挪位置，松手时才定 */
    private int pressedAppIndex = -1;

    /** 按下点的本地坐标，用来量挪了多远 */
    private double pressX, pressY;

    private boolean draggingApp;
    private double dragX, dragY;

    /** 拖动时松手会插到哪儿（全局下标） */
    private int dragTargetIndex = -1;

    /** 拖着图标停在屏幕哪一边（-1 左 / 0 没有 / 1 右），停够久就翻页 */
    private int edgeDwellSide;
    private long edgeDwellStartMs;

    /** 松手判定为"点开"的那个 App，等 PhoneScreen 来取 */
    private IPhoneApp pendingLaunch;

    /** localMouse 是已撤掉开机缩放的本地坐标；nowMs 由调用方取一次传进来，同一帧里翻页动画与边缘停留要对齐 */
    public void render(GuiGraphics g, int phoneLeft, int phoneTop, Font font,
                       long nowMs, boolean animationDone,
                       double localMouseX, double localMouseY) {
        this.phoneLeft = phoneLeft;
        this.phoneTop = phoneTop;

        this.gridStartX = phoneLeft + PhoneTheme.APP_GRID_PADDING_LEFT;
        this.gridStartY = phoneTop + PhoneTheme.STATUS_BAR_HEIGHT
                + PhoneTheme.APP_GRID_PADDING_TOP;

        this.font = font;
        this.nowMs = nowMs;
        this.animationDone = animationDone;

        // 翻页动画走完在这里清起点，slideProgress 保持纯查询
        if (pageSlideStartMs > 0 && nowMs - pageSlideStartMs >= PhoneTheme.PAGE_SLIDE_MS) {
            pageSlideStartMs = 0;
        }

        renderAppGrid(g);

        updateAppHover(localMouseX, localMouseY);
    }

    /** 返回 false 表示按在空处，由 PhoneScreen 决定是机身内空白（调 {@link #pressBlank}）还是机身外 */
    public boolean mousePressed(double lx, double ly) {
        int dot = hitTestPageDot(lx, ly, pageCount());
        if (dot >= 0) {
            homePage = dot;
            hoveredAppIndex = -1;
            return true;
        }

        if (hoveredAppIndex >= 0) {
            pressedAppIndex = hoveredAppIndex;
            pressX = lx;
            pressY = ly;
            dragTargetIndex = pressedAppIndex;
            draggingApp = false;
            return true;
        }
        return false;
    }

    /** 在机身内的空白处按下了，横着拖就是翻页 */
    public void pressBlank(double lx, double ly) {
        pressedBlank = true;
        pressX = lx;
        pressY = ly;
    }

    public boolean mouseDragged(double lx, double ly) {
        if (pressedAppIndex >= 0) {
            if (!draggingApp) {
                if (Math.abs(lx - pressX) < PhoneTheme.APP_DRAG_THRESHOLD
                        && Math.abs(ly - pressY) < PhoneTheme.APP_DRAG_THRESHOLD) {
                    return true;
                }
                draggingApp = true;
            }

            dragX = lx;
            dragY = ly;
            dragTargetIndex = dropIndexAt(lx, ly, PhoneScreenRegistry.getAppCount());
            return true;
        }

        if (pressedBlank) {
            double moved = lx - pressX;
            if (Math.abs(moved) >= PhoneTheme.PAGE_SWIPE_THRESHOLD) {
                goToPage(homePage + (moved < 0 ? 1 : -1));
                // 翻没翻成都重设起点，否则到头后按住不动会每帧重复触发
                pressX = lx;
            }
            return true;
        }
        return false;
    }

    /** 松手才定性：这一下算"点开"还是"挪位置" */
    public boolean mouseReleased(double lx, double ly) {
        if (pressedBlank) {
            pressedBlank = false;
            return true;
        }
        if (pressedAppIndex >= 0) {
            int from = pressedAppIndex;
            int to = dragTargetIndex;
            boolean dragged = draggingApp;

            // 先清状态再动作：调用方拿到 launch 请求后可能当场跳走
            pressedAppIndex = -1;
            dragTargetIndex = -1;
            draggingApp = false;

            if (dragged) {
                PhoneScreenRegistry.moveApp(from, to);
                // 顺序变了，旧 hover 下标已不指向同一个 App
                hoveredAppIndex = -1;
            } else {
                pendingLaunch = PhoneScreenRegistry.getApp(from);
            }
            return true;
        }
        return false;
    }

    /** 滚轮翻页。只有一页或到头时也返回 true，把滚轮吃掉 */
    public boolean mouseScrolled(double scrollY) {
        if (scrollY == 0) return false;
        goToPage(homePage + (scrollY > 0 ? -1 : 1));
        return true;
    }

    /** 松手判定为"点开"的那个 App；没有则 null。取走即清 */
    public IPhoneApp consumeLaunchRequest() {
        IPhoneApp out = pendingLaunch;
        pendingLaunch = null;
        return out;
    }

    private void renderAppGrid(GuiGraphics g) {
        final int is = PhoneTheme.APP_ICON_SIZE;
        final int pageSize = pageSize();

        // 先结算边缘停留，这一帧可能翻页，之后的预览才对
        updateEdgePageFlip();

        // 只有拖动时才拷一份：getApps() 是只读视图，reorder 会就地改
        List<IPhoneApp> ordered = PhoneScreenRegistry.getApps();
        IPhoneApp floatingApp = null;
        int floatingIndex = -1;
        if (draggingApp && pressedAppIndex >= 0 && pressedAppIndex < ordered.size()) {
            ordered = new ArrayList<>(ordered);
            floatingApp = ordered.get(pressedAppIndex);
            floatingIndex = Math.max(0, Math.min(dragTargetIndex, ordered.size() - 1));
            HomeLayout.reorder(ordered, pressedAppIndex, floatingIndex);
        }

        // 每帧夹一次页码：卸载 App、换存档都可能让它指到不存在的页
        homePage = HomeLayout.clampPage(homePage, ordered.size(), pageSize);

        float slide = slideProgress();
        if (slide >= 1f) {
            renderPageIcons(g, ordered, homePage, 0, floatingIndex);
        } else {
            int dir = homePage > slideFromPage ? 1 : -1;
            int w = PhoneTheme.PHONE_WIDTH;
            int inX = Math.round((1f - slide) * dir * w);

            g.enableScissor(phoneLeft, phoneTop + PhoneTheme.STATUS_BAR_HEIGHT,
                    phoneLeft + w, dotsTop());
            renderPageIcons(g, ordered, slideFromPage, inX - dir * w, floatingIndex);
            renderPageIcons(g, ordered, homePage, inX, floatingIndex);
            g.disableScissor();
        }

        renderPageDots(g, HomeLayout.pageCount(ordered.size(), pageSize));
        renderEdgeHint(g);

        // 浮起的那张最后画，才盖在别的图标上面
        if (floatingApp != null) {
            int fx = (int) dragX - is / 2;
            int fy = (int) dragY - is / 2;
            floatingApp.renderIcon(g, fx, fy, is, 0);
            drawAppName(g, floatingApp.getDisplayName().getString(), fx, fy, is);
        }
    }

    /** 画某一页的图标。xOffset 是整页横向偏移（翻页动画用）；floatingIndex 是正被拖着的下标，-1 表示没有 */
    private void renderPageIcons(GuiGraphics g, List<IPhoneApp> ordered,
                                 int page, int xOffset, int floatingIndex) {
        final int is = PhoneTheme.APP_ICON_SIZE;
        final int cols = PhoneTheme.APP_COLUMNS;
        final int cellW = is + PhoneTheme.APP_GRID_SPACING_X;
        final int cellH = appCellHeight();
        final int pageSize = pageSize();
        final int start = page * pageSize;

        for (int slot = 0; slot < pageSize; slot++) {
            int i = start + slot;
            if (i < 0 || i >= ordered.size()) break;

            int ix = gridStartX + (slot % cols) * cellW + xOffset;
            int iy = gridStartY + (slot / cols) * cellH;

            // 被拖的那一格只留空槽，它本人跟着鼠标走，最后单独画
            if (i == floatingIndex) {
                PhoneSkin.drawOrFill(g, PhoneSkin.Element.HOME_DROP_SLOT,
                        ix, iy, is, is, PhoneTheme.COLOR_APP_DROP_SLOT);
                continue;
            }

            if (i == hoveredAppIndex && !draggingApp) {
                g.fill(ix - 2, iy - 2, ix + is + 2, iy + is + 2, PhoneTheme.COLOR_APP_PRESSED);
            }

            IPhoneApp app = ordered.get(i);
            app.renderIcon(g, ix, iy, is, 0);

            drawBadge(g, app, ix, iy, is);
            drawAppName(g, app.getDisplayName().getString(), ix, iy, is);
        }
    }

    /**
     * 图标右上角的未读角标，0 不画。压出图标 2 像素——真手机就是这么摆的，
     * 而网格左右各留了 8 像素，压不出屏幕。
     */
    private void drawBadge(GuiGraphics g, IPhoneApp app, int ix, int iy, int is) {
        int count = badgeCountOf(app);
        if (count <= 0) return;

        String label = count > 99 ? "99+" : String.valueOf(count);
        int textW = font.width(label);
        int w = Math.max(font.lineHeight + 1, textW + 4);
        int h = font.lineHeight + 1;
        int x = ix + is - w + 2;
        int y = iy - 2;

        // 与会话列表、通知共用一张贴图，换肤时三处一致
        PhoneSkin.drawOrFill(g, PhoneSkin.Element.UNREAD_BADGE, x, y, w, h,
                PhoneTheme.COLOR_UNREAD_BADGE);
        g.drawString(font, label, x + (w - textW) / 2, y + 1,
                PhoneTheme.FONT_COLOR_BADGE, false);
    }

    /** 读一个 App 的角标数，读不出来当没有。兜 Throwable：附属 App 可能引用没装的模组类 */
    private static int badgeCountOf(IPhoneApp app) {
        try {
            return app.getBadgeCount();
        } catch (Throwable t) {
            // 每帧每个图标问一次，出了事这里会刷屏，所以只记 debug
            MCphone.LOGGER.debug("[MCphone] 读 {} 的角标数失败，当作没有",
                    app.getClass().getName(), t);
            return 0;
        }
    }

    /**
     * 拖着图标停在屏幕左右边上时自动翻页。
     * 每帧算一次而不是挂在 mouseDragged 上：鼠标不动就没有事件，计时器走不完。
     */
    private void updateEdgePageFlip() {
        if (!draggingApp) {
            edgeDwellSide = 0;
            edgeDwellStartMs = 0;
            return;
        }

        final int count = PhoneScreenRegistry.getAppCount();
        final int pageSize = pageSize();

        int side = 0;
        if (dragX < phoneLeft + PhoneTheme.PAGE_EDGE_WIDTH) {
            side = -1;
        } else if (dragX > phoneLeft + PhoneTheme.PHONE_WIDTH - PhoneTheme.PAGE_EDGE_WIDTH) {
            side = 1;
        }

        if (side != 0
                && HomeLayout.clampPage(homePage + side, count, pageSize) == homePage) {
            side = 0;
        }

        if (side != edgeDwellSide) {
            edgeDwellSide = side;
            edgeDwellStartMs = nowMs;
        }
        if (side == 0) return;

        if (nowMs - edgeDwellStartMs >= PhoneTheme.PAGE_EDGE_DWELL_MS) {
            goToPage(homePage + side);
            edgeDwellStartMs = nowMs;   // 按住不放就接着往下翻

            // 页变了，同一鼠标位置的落点也变了
            dragTargetIndex = dropIndexAt(dragX, dragY, count);
        }
    }

    /** 边缘提示条，随停留时长由浅到深 */
    private void renderEdgeHint(GuiGraphics g) {
        if (edgeDwellSide == 0 || !draggingApp) return;

        float progress = Math.min(1f,
                (float) (nowMs - edgeDwellStartMs) / Math.max(1, PhoneTheme.PAGE_EDGE_DWELL_MS));

        final int w = PhoneTheme.PAGE_EDGE_WIDTH;
        int x = edgeDwellSide < 0 ? phoneLeft : phoneLeft + PhoneTheme.PHONE_WIDTH - w;
        int top = phoneTop + PhoneTheme.STATUS_BAR_HEIGHT;
        int h = dotsTop() - top;

        // setColor 调制透明度后必须还原，否则后面的东西跟着变淡
        g.setColor(1f, 1f, 1f, progress);
        boolean drawn = PhoneSkin.draw(g, PhoneSkin.Element.HOME_PAGE_EDGE, x, top, w, h);
        g.setColor(1f, 1f, 1f, 1f);

        if (!drawn) {
            int alpha = (int) (progress * ((PhoneTheme.COLOR_PAGE_EDGE >>> 24) & 0xFF));
            g.fill(x, top, x + w, top + h,
                    (alpha << 24) | (PhoneTheme.COLOR_PAGE_EDGE & 0x00FFFFFF));
        }
    }

    /** 翻页动画进度，1 表示已经停稳 */
    private float slideProgress() {
        if (pageSlideStartMs <= 0 || PhoneTheme.PAGE_SLIDE_MS <= 0) return 1f;

        long elapsed = nowMs - pageSlideStartMs;
        if (elapsed >= PhoneTheme.PAGE_SLIDE_MS) return 1f;

        float t = (float) elapsed / PhoneTheme.PAGE_SLIDE_MS;
        return 1f - (1f - t) * (1f - t);
    }

    /** 底部页码点，只有一页时不画 */
    private void renderPageDots(GuiGraphics g, int pages) {
        if (pages <= 1) return;

        final int size = PhoneTheme.PAGE_DOT_SIZE;
        final int gap = PhoneTheme.PAGE_DOT_SPACING;

        int x = phoneLeft + (PhoneTheme.PHONE_WIDTH - (pages * size + (pages - 1) * gap)) / 2;
        int y = dotsTop() + (PhoneTheme.PAGE_DOTS_HEIGHT - size) / 2;

        for (int p = 0; p < pages; p++) {
            boolean active = p == homePage;
            PhoneSkin.drawOrFill(g,
                    active ? PhoneSkin.Element.HOME_PAGE_DOT_ACTIVE
                           : PhoneSkin.Element.HOME_PAGE_DOT,
                    x, y, size, size,
                    active ? PhoneTheme.COLOR_PAGE_DOT_ACTIVE : PhoneTheme.COLOR_PAGE_DOT);
            x += size + gap;
        }
    }

    /** 点在了第几个页码点上，没点中返回 -1。判定区比点本身大一圈 */
    private int hitTestPageDot(double lx, double ly, int pages) {
        if (pages <= 1) return -1;

        int top = dotsTop();
        if (ly < top || ly >= top + PhoneTheme.PAGE_DOTS_HEIGHT) return -1;

        final int size = PhoneTheme.PAGE_DOT_SIZE;
        final int gap = PhoneTheme.PAGE_DOT_SPACING;
        final int step = size + gap;

        int x = phoneLeft + (PhoneTheme.PHONE_WIDTH - (pages * size + (pages - 1) * gap)) / 2;
        int idx = (int) Math.floor((lx - (x - gap / 2.0)) / step);
        return (idx >= 0 && idx < pages) ? idx : -1;
    }

    /** 主屏一格的高度：图标加底下那行名字 */
    private int appCellHeight() {
        return PhoneTheme.APP_ICON_SIZE
                + (int)(font.lineHeight * PhoneTheme.APP_NAME_SCALE) + 4;
    }

    /** 页码点那一条的顶边。图标区到此为止，再往下是导航栏 */
    private int dotsTop() {
        return phoneTop + PhoneTheme.PHONE_HEIGHT
                - PhoneTheme.NAV_BAR_HEIGHT - PhoneTheme.PAGE_DOTS_HEIGHT;
    }

    private int rowsPerPage() {
        return HomeLayout.rowsThatFit(dotsTop() - gridStartY, appCellHeight(), PhoneTheme.APP_ROWS);
    }

    private int pageSize() {
        return PhoneTheme.APP_COLUMNS * rowsPerPage();
    }

    private int pageCount() {
        return HomeLayout.pageCount(PhoneScreenRegistry.getAppCount(), pageSize());
    }

    /** 翻到第几页，真的换页了才 true */
    private boolean goToPage(int page) {
        int target = HomeLayout.clampPage(page, PhoneScreenRegistry.getAppCount(), pageSize());
        if (target == homePage) return false;

        slideFromPage = homePage;
        homePage = target;

        // 开机动画里不滑：裁剪矩形按屏幕坐标算，与缩放中的手机对不上
        pageSlideStartMs = animationDone ? System.currentTimeMillis() : 0;

        hoveredAppIndex = -1;
        return true;
    }

    /** 鼠标位置对应的落点（全局下标，含页偏移） */
    private int dropIndexAt(double lx, double ly, int count) {
        if (count <= 0) return -1;

        int slot = HomeLayout.slotAt(lx, ly, gridStartX, gridStartY,
                PhoneTheme.APP_ICON_SIZE + PhoneTheme.APP_GRID_SPACING_X, appCellHeight(),
                PhoneTheme.APP_COLUMNS, rowsPerPage());

        return HomeLayout.dropIndex(homePage, slot, pageSize(), count);
    }

    /** 图标下面那行名字。可用宽度是格子步距而不是图标宽 */
    private void drawAppName(GuiGraphics g, String name, int ix, int iy, int is) {
        GuiUtil.drawIconLabel(g, font, name, ix, iy, is,
                is + PhoneTheme.APP_GRID_SPACING_X,
                PhoneTheme.APP_NAME_SCALE, FontPalette.appName());
    }

    private void updateAppHover(double localX, double localY) {
        int lx = (int) localX;
        int ly = (int) localY;

        final int count = PhoneScreenRegistry.getAppCount();
        final int is = PhoneTheme.APP_ICON_SIZE;
        final int cols = PhoneTheme.APP_COLUMNS;
        final int cellW = is + PhoneTheme.APP_GRID_SPACING_X;
        final int cellH = appCellHeight();
        final int pageSize = pageSize();
        final int start = homePage * pageSize;

        hoveredAppIndex = -1;

        // 翻页动画里不认 hover：图标还在半路上
        if (slideProgress() < 1f) return;
        for (int slot = 0; slot < pageSize; slot++) {
            int i = start + slot;
            if (i >= count) break;

            int ix = gridStartX + (slot % cols) * cellW;
            int iy = gridStartY + (slot / cols) * cellH;
            if (lx >= ix && lx <= ix + is && ly >= iy && ly <= iy + is + 6) {
                hoveredAppIndex = i;   // 全局下标，不是 slot
                return;
            }
        }
    }
}
