package com.november.mcphone.core.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.core.client.home.HomeArrangement;
import com.november.mcphone.core.client.home.HomeInput;
import com.november.mcphone.core.client.home.HomeInput.FolderDrag;
import com.november.mcphone.core.client.home.HomeReflow;
import com.november.mcphone.core.client.home.HomeReflow.Position;
import com.november.mcphone.platform.ResourceIds;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主屏 —— App 图标网格、拖动排序、分页与翻页。
 * 只认手机本地坐标：四个输入方法收到的一律是已撤掉开机缩放的坐标，反变换留在 PhoneScreen。
 * 点开 App 只记进 {@link #consumeOpenRequest()}，由 PhoneScreen 取走再决定怎么开。
 * 输入方法用的上下文（机身位置、字体、时刻）是上一帧 {@link #render} 带进来的。
 */
public final class HomeGrid {
    private static final ResourceLocation FOLDER_PLATE =
            ResourceIds.create(MCphone.MODID, "textures/phone/home_folder_plate.png");
    private static final int FOLDER_PLATE_TEXTURE_SIZE = 80;
    private static final int DROP_ACCENT = 0xFFBFE6FF;
    private static final int CUE_SCALE = 8;
    private static final int PLATE_RADIUS = 2 * CUE_SCALE;
    private static final int PLATE_EDGE = 5;
    private static final float DRAG_OPACITY = 0.56f;
    private static final float MERGE_DRAG_OPACITY = 0.32f;
    private static final double MERGE_EXIT_MARGIN = 1.0;
    private static final int PAGE_EDGE_GUTTER = 3;
    private static final int CUE_FADE_MS = 80;

    private record DropTarget(String groupId, HomeLayout.Insertion insertion) {}

    private static final DropTarget NO_DROP = new DropTarget(null, null);

    private int phoneLeft, phoneTop;

    /**
     * 这一帧画的是哪台设备的屏幕。与 phoneLeft/phoneTop 一样由 {@link #render} 带进来，
     * 输入方法（点击、拖动）沿用上一帧那份——列数、页码点位置、边缘翻页的判定都要它。
     *
     * 有初值是因为输入有可能<b>先于</b>第一帧到（界面刚开就点了一下）。那一下算在手机
     * 尺寸上，最坏是点空，不会空指针。
     */
    private DeviceMetrics metrics = DeviceMetrics.PHONE;

    /**
     * 本帧的插值系数，转交给 {@link IPhoneApp#renderIcon} —— 图标是可以动的（覆盖那个方法
     * 自己画就行），而动画要平滑就得有它。
     *
     * 存成字段而不是一路传参：画图标那两处都在私有方法里，为一个只往下传不参与计算的值
     * 给它们各加一个参数不值当。
     */
    private float partialTick;
    private int gridStartX, gridStartY;
    private Font font;
    private long nowMs;

    /** 鼠标停在第几个 App 上（全局下标），-1 表示没有 */
    private int hoveredAppIndex = -1;

    private int homePage = 0;
    private final HomeInput input = new HomeInput(PhoneTheme.APP_DRAG_THRESHOLD, PhoneTheme.PAGE_SWIPE_THRESHOLD);
    private double grabX, grabY;
    private FolderDrag folderDrag;
    private DropTarget preview = NO_DROP;
    private long previewSinceMs;
    private long frameDeltaMs;
    private float mergeAmount;
    private HomeReflow reflow = HomeReflow.NONE;
    private int reflowPage;
    private long reflowElapsedMs;
    private long reflowFrameMs;

    /** 翻页动画：从哪一页滑过来的，什么时候开始滑 */
    private int slideFromPage;
    private long pageSlideStartMs;

    /** 拖着图标停在屏幕哪一边（-1 左 / 0 没有 / 1 右），停够久就翻页 */
    private int edgeDwellSide;
    private long edgeDwellStartMs;

    /** 松手判定为"点开"的那个 App，等 PhoneScreen 来取 */
    private HomeArrangement.Entry pendingOpen;

    /** localMouse 是已撤掉开机缩放的本地坐标；nowMs 由调用方取一次传进来，同一帧里翻页动画与边缘停留要对齐 */
    public void render(GuiGraphics g, int phoneLeft, int phoneTop, DeviceMetrics metrics, Font font,
                       long nowMs,
                       double localMouseX, double localMouseY, float partialTick) {
        layout(phoneLeft, phoneTop, metrics, font);
        this.partialTick = partialTick;
        // 留住上一帧真正画出的位置。动画中的图标按下后可以原地抓住，直到这次拖动结束。
        if (!input.pressed() && !reflow.isEmpty()) {
            reflowElapsedMs += Math.max(0, nowMs - reflowFrameMs);
            if (reflowElapsedMs >= HomeReflow.DURATION_MS) reflow = HomeReflow.NONE;
        }
        reflowFrameMs = nowMs;
        frameDeltaMs = Math.min(50, Math.max(0, nowMs - this.nowMs));
        this.nowMs = nowMs;

        if (pageSlideStartMs > 0 && nowMs - pageSlideStartMs >= PhoneTheme.PAGE_SLIDE_MS) {
            pageSlideStartMs = 0;
        }
        homePage = HomeLayout.clampPage(homePage, entryCount(), pageSize());
        hoveredAppIndex = hitEntry(localMouseX, localMouseY, false);
        renderAppGrid(g);
    }

    private void layout(int phoneLeft, int phoneTop, DeviceMetrics metrics, Font font) {
        if (this.phoneLeft != phoneLeft || this.phoneTop != phoneTop || !this.metrics.equals(metrics)
                || (this.font != null && this.font.lineHeight != font.lineHeight)) {
            reflow = HomeReflow.NONE;
            cancelInput();
        }
        this.phoneLeft = phoneLeft;
        this.phoneTop = phoneTop;
        this.metrics = metrics;
        this.font = font;

        // 图标整排居中，而不是靠左留一道固定边距——手机上算出来仍是 8，平板上是 5。
        // 居中的地方是【内容区】而不是整块屏幕：平板的导航条立在右边，把那 14 也算进来
        // 的话整排图标会往右偏 7 个像素，右边那一列压在导航条底下
        this.gridStartX = phoneLeft + HomeLayout.gridInset(
                metrics.contentWidth(), columns(), appCellWidth(), PhoneTheme.APP_GRID_SPACING_X);
        this.gridStartY = phoneTop + PhoneTheme.STATUS_BAR_HEIGHT
                + PhoneTheme.APP_GRID_PADDING_TOP;

    }

    public boolean mousePressed(double lx, double ly) {
        int dot = hitTestPageDot(lx, ly, pageCount());
        if (dot >= 0) {
            cancelInput();
            homePage = dot;
            pageSlideStartMs = 0;
            return true;
        }

        int index = hitEntry(lx, ly, false);
        HomeArrangement.Entry entry = entryAt(index);
        if (entry == null) return false;
        Position origin = displayedPosition(entry.id(), index);
        grabX = lx - origin.x();
        grabY = ly - origin.y();
        input.press(entry.id(), lx, ly);
        return true;
    }

    public void pressBlank(double lx, double ly) {
        input.press(null, lx, ly);
    }

    public void continueFolderDrag(FolderDrag outbound, double lx, double ly,
                                   int phoneLeft, int phoneTop, DeviceMetrics metrics, Font font) {
        layout(phoneLeft, phoneTop, metrics, font);
        cancelInput();
        reflow = HomeReflow.NONE;
        pageSlideStartMs = 0;
        mergeAmount = 0f;
        grabX = PhoneTheme.APP_ICON_SIZE / 2.0;
        grabY = grabX;
        folderDrag = outbound;
        input.continueDrag(outbound.appId(), lx, ly);
    }

    public boolean mouseDragged(double lx, double ly) {
        if (!input.pressed()) return false;
        int step = input.drag(lx, ly);
        if (step != 0) goToPage(homePage + step);
        return true;
    }

    public boolean mouseReleased(double lx, double ly) {
        HomeArrangement.Entry hit = entryAt(hitEntry(lx, ly, false));
        HomeInput.Release release = input.release(hit == null ? null : hit.id(), lx, ly);
        resetEdgeDwell();
        if (release == null) return false;
        reflowFrameMs = System.currentTimeMillis();
        if (release.clicked()) pendingOpen = hit;
        if (release.dragged()) {
            int from = indexOf(release.id());
            Position ghost = dragPosition(lx, ly);
            HomeArrangement.Entry source = dragSource(from);
            if (source != null) commitDrop(source.id(), from, dropAt(source, from, lx, ly, ghost), ghost);
        }
        folderDrag = null;
        preview = NO_DROP;
        return true;
    }

    public boolean cancelDrag() {
        if (!input.pressed() || input.id() == null) return false;
        cancelInput();
        return true;
    }

    public void cancelInput() {
        input.cancel();
        folderDrag = null;
        pendingOpen = null;
        preview = NO_DROP;
        reflowFrameMs = System.currentTimeMillis();
        resetEdgeDwell();
    }

    private void resetEdgeDwell() {
        edgeDwellSide = 0;
        edgeDwellStartMs = 0;
    }

    public boolean mouseScrolled(double scrollY) {
        if (scrollY == 0) return false;
        cancelInput();
        goToPage(homePage + (scrollY > 0 ? -1 : 1));
        return true;
    }

    public HomeArrangement.Entry consumeOpenRequest() {
        HomeArrangement.Entry entry = pendingOpen;
        pendingOpen = null;
        return entry;
    }

    private void renderAppGrid(GuiGraphics g) {
        List<HomeArrangement.Entry> ordered = PhoneScreenRegistry.homeEntries();
        int from = input.dragging() ? indexOf(input.id()) : -1;
        HomeArrangement.Entry floating = input.dragging() ? dragSource(from) : null;
        Position ghost = dragPosition(input.x(), input.y());
        DropTarget next = floating == null ? NO_DROP : dropAt(floating, from, input.x(), input.y(), ghost);
        if (!next.equals(preview)) previewSinceMs = nowMs;
        preview = next;
        if (preview.groupId() == null) updateEdgePageFlip();
        else resetEdgeDwell();
        float fadeStep = (float) frameDeltaMs / CUE_FADE_MS;
        if (floating == null) mergeAmount = 0f;
        else if (preview.groupId() == null) mergeAmount = Math.max(0f, mergeAmount - fadeStep);
        else mergeAmount = Math.min(1f, mergeAmount + fadeStep);

        float slide = slideProgress();
        if (slide >= 1f) {
            renderPageIcons(g, ordered, homePage, 0, from);
        } else {
            int dir = homePage > slideFromPage ? 1 : -1;
            int w = metrics.contentWidth();
            int inX = Math.round((1f - slide) * dir * w);

            GuiUtil.enableScissor(g, phoneLeft, phoneTop + PhoneTheme.STATUS_BAR_HEIGHT,
                    phoneLeft + w, dotsTop());
            renderPageIcons(g, ordered, slideFromPage, inX - dir * w, from);
            renderPageIcons(g, ordered, homePage, inX, from);
            GuiUtil.disableScissor(g);
        }

        renderPageDots(g, HomeLayout.pageCount(ordered.size(), pageSize()));
        renderEdgeHint(g);

        if (floating != null) {
            GuiUtil.clipped(g, phoneLeft, phoneTop + PhoneTheme.STATUS_BAR_HEIGHT,
                    phoneLeft + metrics.contentWidth(), dotsTop(), () -> drawDropPreview(g, floating, ghost));
        }
    }

    private void renderPageIcons(GuiGraphics g, List<HomeArrangement.Entry> ordered,
                                 int page, int xOffset, int sourceIndex) {
        int size = PhoneTheme.APP_ICON_SIZE;
        int start = page * pageSize();
        for (int slot = 0; slot < pageSize() && start + slot < ordered.size(); slot++) {
            int i = start + slot;
            HomeArrangement.Entry entry = ordered.get(i);
            if (entry.id().equals(preview.groupId())) continue;
            Position position = displayedPosition(entry.id(), i);
            int ix = (int) Math.floor(position.x()) + xOffset;
            int iy = (int) Math.floor(position.y());
            boolean source = i == sourceIndex;
            boolean hovered = i == hoveredAppIndex && !input.dragging();
            withOffset(g, position.x() + xOffset - ix, position.y() - iy, () -> {
                if (source) drawDragPlate(g, ix - 1, iy - 1, size + 2, appCellHeight(), 0x18C8DFFF, 0x48B9D8FF);
                else if (hovered) g.fill(ix - 2, iy - 2, ix + size + 2, iy + size + 2, PhoneTheme.COLOR_APP_PRESSED);
                drawEntry(g, entry, ix, iy, size);
            });
        }
    }

    private void drawEntry(GuiGraphics g, HomeArrangement.Entry entry, int ix, int iy, int size) {
        if (entry instanceof HomeArrangement.Folder folder) {
            drawFolderImage(g, folder.members(), ix, iy, size, 1f);
            drawFolderBadge(g, folder.members(), ix, iy, size);
            drawAppName(g, folder.name(), ix, iy, size);
            return;
        }
        IPhoneApp app = PhoneScreenRegistry.homeApp(entry.id());
        if (app == null) return;
        app.renderIcon(g, ix, iy, size, partialTick);
        drawBadge(g, app, ix, iy, size);
        drawAppName(g, app.getDisplayName().getString(), ix, iy, size);
    }

    private void drawFolderImage(GuiGraphics g, List<String> members, int x, int y, int size, float opacity) {
        g.setColor(1f, 1f, 1f, opacity);
        try {
            GuiUtil.drawTexture(g, FOLDER_PLATE, x, y, size, size,
                    FOLDER_PLATE_TEXTURE_SIZE, FOLDER_PLATE_TEXTURE_SIZE);
        } finally {
            g.setColor(1f, 1f, 1f, 1f);
        }
        drawFolderMembers(g, members, x, y, size, opacity);
    }

    private void drawFolderMembers(GuiGraphics g, List<String> members, int x, int y, int size, float opacity) {
        int tile = (size - 6) / 2;
        for (int i = 0; i < Math.min(4, members.size()); i++) {
            IPhoneApp app = PhoneScreenRegistry.homeApp(members.get(i));
            if (app == null) continue;
            int ix = x + 2 + (i % 2) * (tile + 2);
            int iy = y + 2 + (i / 2) * (tile + 2);
            if (opacity == 1f) app.renderIcon(g, ix, iy, tile, partialTick);
            else HomeDragIcon.draw(g, app, ix, iy, tile, partialTick, opacity);
        }
    }

    private void drawFolderBadge(GuiGraphics g, List<String> members, int x, int y, int size) {
        int unread = 0;
        for (String id : members) {
            IPhoneApp app = PhoneScreenRegistry.homeApp(id);
            if (app != null) unread += Math.min(100, Math.max(0, UnreadBadge.count(app)));
            if (unread > 99) break;
        }
        drawBadge(g, unread, x, y, size);
    }

    private void drawDropPreview(GuiGraphics g, HomeArrangement.Entry source, Position ghost) {
        int size = PhoneTheme.APP_ICON_SIZE;
        int x = (int) Math.floor(ghost.x());
        int y = (int) Math.floor(ghost.y());
        withOffset(g, ghost.x() - x, ghost.y() - y, () -> drawGhost(g, source, x, y, size));
        if (preview.groupId() != null) drawGroupPreview(g, source.id(), indexOf(preview.groupId()), size);
        else if (preview.insertion() != null) drawInsertion(g, preview.insertion(), size);
    }

    private void drawGhost(GuiGraphics g, HomeArrangement.Entry source, int x, int y, int size) {
        float opacity = DRAG_OPACITY + (MERGE_DRAG_OPACITY - DRAG_OPACITY) * mergeAmount;
        String name;
        if (source instanceof HomeArrangement.Folder folder) {
            drawFolderImage(g, folder.members(), x, y, size, opacity);
            name = folder.name();
        } else {
            IPhoneApp app = PhoneScreenRegistry.homeApp(source.id());
            if (app == null) return;
            HomeDragIcon.draw(g, app, x, y, size, partialTick, opacity);
            name = app.getDisplayName().getString();
        }
        float labelOpacity = opacity * (1f - mergeAmount);
        if (labelOpacity >= 0.08f) {
            GuiUtil.drawIconLabel(g, font, name, x, y, size, appCellWidth(),
                    PhoneTheme.APP_NAME_SCALE, withOpacity(FontPalette.appName(), labelOpacity));
        }
    }

    private void drawGroupPreview(GuiGraphics g, String sourceId, int targetIndex, int size) {
        HomeArrangement.Entry target = entryAt(targetIndex);
        if (target == null) return;
        Position position = displayedPosition(target.id(), targetIndex);
        int x = (int) Math.floor(position.x());
        int y = (int) Math.floor(position.y());
        withOffset(g, position.x() - x, position.y() - y, () -> drawGroupTile(g, target, sourceId, x, y, size));
    }

    private void drawGroupTile(GuiGraphics g, HomeArrangement.Entry target, String sourceId, int x, int y, int size) {
        drawDragPlate(g, x - 2, y - 2, size + 4, size + 4, 0xE8485163, withOpacity(DROP_ACCENT, cueOpacity() * 0.8f));
        if (target instanceof HomeArrangement.Folder folder) {
            List<String> members = new ArrayList<>(folder.members().subList(0, Math.min(4, folder.members().size())));
            members.add(sourceId);
            drawFolderMembers(g, members, x, y, size, 1f);
            drawAddBadge(g, x + size - 0.5f, y + 0.5f);
            drawAppName(g, folder.name(), x, y, size);
        } else {
            drawFolderMembers(g, List.of(target.id(), sourceId), x, y, size, 1f);
            drawAppName(g, Component.translatable("mcphone.home.folder.default").getString(), x, y, size);
        }
    }

    private static void drawAddBadge(GuiGraphics g, float centerX, float centerY) {
        g.pose().pushPose();
        try {
            g.pose().translate(centerX, centerY, 0);
            g.pose().scale(1f / CUE_SCALE, 1f / CUE_SCALE, 1f);
            drawRoundedRect(g, -15, -15, 15, 15, 15, 0xDA25354D);
            // 淡边在小 GUI 比例下仍能托住较细的浅色笔画。
            drawPlus(g, 10, 4, 0x65D5E9FF);
            drawPlus(g, 9, 3, 0xF4EDF6FF);
        } finally {
            g.pose().popPose();
        }
    }

    private static void drawPlus(GuiGraphics g, int arm, int stroke, int color) {
        g.fill(-stroke, -arm, stroke, arm, color);
        g.fill(-arm, -stroke, -stroke, stroke, color);
        g.fill(stroke, -stroke, arm, stroke, color);
    }

    private void drawInsertion(GuiGraphics g, HomeLayout.Insertion insertion, int size) {
        float opacity = cueOpacity();
        g.pose().pushPose();
        try {
            g.pose().translate((float) insertion.x() - 0.5f / CUE_SCALE, (float) insertion.y(), 0);
            g.pose().scale(1f / CUE_SCALE, 1f / CUE_SCALE, 1f);
            int height = size * CUE_SCALE;
            // HTML 的 2px 对应约 0.8 个逻辑像素。淡边让低缩放时的线芯仍然可辨。
            drawRoundedRect(g, -17, -12, 18, height + 12, 17, withOpacity(0x0B94D7FF, opacity));
            drawRoundedRect(g, -10, -6, 11, height + 6, 10, withOpacity(0x1994D7FF, opacity));
            drawRoundedRect(g, -4, -1, 5, height + 1, 4, withOpacity(0x5094D7FF, opacity));
            drawRoundedRect(g, -3, 0, 4, height, 3, withOpacity(DROP_ACCENT, opacity));
        } finally {
            g.pose().popPose();
        }
    }

    private float cueOpacity() {
        return 0.62f + 0.3f * Math.min(1f, Math.max(0f, (float) (nowMs - previewSinceMs) / CUE_FADE_MS));
    }

    private static void drawDragPlate(GuiGraphics g, int x, int y, int width, int height, int fill, int border) {
        g.pose().pushPose();
        try {
            g.pose().translate(x, y, 0);
            g.pose().scale(1f / CUE_SCALE, 1f / CUE_SCALE, 1f);
            int w = width * CUE_SCALE;
            int h = height * CUE_SCALE;
            int radius = PLATE_RADIUS;
            int feather = withOpacity(border, 0.35f);
            // 亮边占 5/8 像素，余下 3/8 用较淡的颜色接到底色。整圈保留一个像素的覆盖。
            drawPlateBand(g, w, radius, h - radius, 0, PLATE_EDGE, CUE_SCALE, fill, border, feather);
            for (int row = 0; row < radius;) {
                int outer = cornerInset(radius, row);
                int edge = plateInset(radius, row, PLATE_EDGE, w / 2);
                int inner = plateInset(radius, row, CUE_SCALE, w / 2);
                int end = row + 1;
                while (end < radius && cornerInset(radius, end) == outer
                        && plateInset(radius, end, PLATE_EDGE, w / 2) == edge
                        && plateInset(radius, end, CUE_SCALE, w / 2) == inner) end++;
                drawPlateBand(g, w, row, end, outer, edge, inner, fill, border, feather);
                drawPlateBand(g, w, h - end, h - row, outer, edge, inner, fill, border, feather);
                row = end;
            }
        } finally {
            g.pose().popPose();
        }
    }

    private static int plateInset(int radius, int row, int padding, int halfWidth) {
        return row < padding ? halfWidth : padding + cornerInset(radius - padding, row - padding);
    }

    private static void drawPlateBand(GuiGraphics g, int width, int top, int bottom,
                                      int outer, int edge, int inner, int fill, int border, int feather) {
        if (edge * 2 == width) {
            g.fill(outer, top, width - outer, bottom, border);
        } else {
            g.fill(outer, top, edge, bottom, border);
            g.fill(width - edge, top, width - outer, bottom, border);
            if (inner * 2 == width) {
                g.fill(edge, top, width - edge, bottom, feather);
            } else {
                g.fill(edge, top, inner, bottom, feather);
                g.fill(inner, top, width - inner, bottom, fill);
                g.fill(width - inner, top, width - edge, bottom, feather);
            }
        }
    }

    private static void drawRoundedRect(GuiGraphics g, int left, int top, int right, int bottom, int radius, int color) {
        if (top + radius < bottom - radius) g.fill(left, top + radius, right, bottom - radius, color);
        for (int row = 0; row < radius;) {
            int inset = cornerInset(radius, row);
            int end = row + 1;
            while (end < radius && cornerInset(radius, end) == inset) end++;
            g.fill(left + inset, top + row, right - inset, top + end, color);
            g.fill(left + inset, bottom - end, right - inset, bottom - row, color);
            row = end;
        }
    }

    private static int cornerInset(int radius, int row) {
        double dy = radius - row - 0.5;
        return (int) Math.round(radius - Math.sqrt(radius * radius - dy * dy));
    }

    private static void withOffset(GuiGraphics g, double x, double y, Runnable draw) {
        if (x == 0 && y == 0) {
            draw.run();
            return;
        }
        g.pose().pushPose();
        try {
            g.pose().translate((float) x, (float) y, 0);
            draw.run();
        } finally {
            g.pose().popPose();
        }
    }

    private static int withOpacity(int color, float opacity) {
        return (color & 0xFFFFFF) | (Math.round((color >>> 24) * opacity) << 24);
    }

    /**
     * 图标右上角的未读角标，0 不画。压出图标 2 像素——真手机就是这么摆的，
     * 而网格左右各留了 8 像素，压不出屏幕。
     */
    private void drawBadge(GuiGraphics g, IPhoneApp app, int ix, int iy, int is) {
        drawBadge(g, UnreadBadge.count(app), ix, iy, is);
    }

    private void drawBadge(GuiGraphics g, int count, int ix, int iy, int is) {
        if (count <= 0) return;

        String label = count > 99 ? "99+" : String.valueOf(count);
        int w = UnreadBadge.width(font, label);
        int x = ix + is - w + 2;
        int y = iy - 2;

        UnreadBadge.draw(g, font, label, x, y);
    }

    /**
     * 拖着图标停在屏幕左右边上时自动翻页。
     * 每帧算一次而不是挂在 mouseDragged 上：鼠标不动就没有事件，计时器走不完。
     */
    private void updateEdgePageFlip() {
        if (!input.dragging() || !insideGridArea(input.x(), input.y())) {
            edgeDwellSide = 0;
            edgeDwellStartMs = 0;
            return;
        }

        int side = pageEdgeSide(input.x(), PhoneTheme.PAGE_EDGE_WIDTH);
        if (side != edgeDwellSide) {
            edgeDwellSide = side;
            edgeDwellStartMs = nowMs;
        }
        if (side == 0) return;

        if (nowMs - edgeDwellStartMs >= PhoneTheme.PAGE_EDGE_DWELL_MS) {
            goToPage(homePage + side);
            edgeDwellStartMs = nowMs;   // 按住不放就接着往下翻

        }
    }

    private int pageEdgeSide(double x, int width) {
        int side = x < phoneLeft + width ? -1 : x >= phoneLeft + metrics.contentWidth() - width ? 1 : 0;
        return side != 0 && HomeLayout.clampPage(homePage + side, entryCount(), pageSize()) != homePage ? side : 0;
    }

    /** 边缘提示条，随停留时长由浅到深 */
    private void renderEdgeHint(GuiGraphics g) {
        if (edgeDwellSide == 0 || !input.dragging()) return;

        float progress = Math.min(1f,
                (float) (nowMs - edgeDwellStartMs) / Math.max(1, PhoneTheme.PAGE_EDGE_DWELL_MS));

        final int w = PhoneTheme.PAGE_EDGE_WIDTH;
        int x = edgeDwellSide < 0 ? phoneLeft : phoneLeft + metrics.contentWidth() - w;
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

        long elapsed = Math.max(0, nowMs - pageSlideStartMs);
        if (elapsed >= PhoneTheme.PAGE_SLIDE_MS) return 1f;

        float t = (float) elapsed / PhoneTheme.PAGE_SLIDE_MS;
        return 1f - (1f - t) * (1f - t);
    }

    /** 底部页码点，只有一页时不画 */
    private void renderPageDots(GuiGraphics g, int pages) {
        if (pages <= 1) return;

        final int size = PhoneTheme.PAGE_DOT_SIZE;
        final int gap = PhoneTheme.PAGE_DOT_SPACING;

        int x = phoneLeft + (metrics.contentWidth() - (pages * size + (pages - 1) * gap)) / 2;
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

        int x = phoneLeft + (metrics.contentWidth() - (pages * size + (pages - 1) * gap)) / 2;
        int idx = (int) Math.floor((lx - (x - gap / 2.0)) / step);
        return (idx >= 0 && idx < pages) ? idx : -1;
    }

    /** 主屏一格的宽度：图标加右边那道间距 */
    private int appCellWidth() {
        return PhoneTheme.APP_ICON_SIZE + PhoneTheme.APP_GRID_SPACING_X;
    }

    /** 主屏一格的高度：图标加底下那行名字 */
    private int appCellHeight() {
        return PhoneTheme.APP_ICON_SIZE
                + (int)((font == null ? 9 : font.lineHeight) * PhoneTheme.APP_NAME_SCALE) + 4;
    }

    /** 一行几个 —— 内容区多宽就排几个，手机 4 个、平板 8 个 */
    private int columns() {
        return HomeLayout.cellsThatFit(metrics.contentWidth(), appCellWidth(), PhoneTheme.APP_COLUMNS_MAX);
    }

    /**
     * 页码点那一条的顶边。图标区到此为止。
     *
     * 底下还有没有导航条要问设备：手机上有（扣 14），平板上那条立在右边，屏幕底下是空的，
     * 页码点就一直贴到最下面。
     */
    private int dotsTop() {
        return phoneTop + metrics.screenH()
                - metrics.bottomNavHeight() - PhoneTheme.PAGE_DOTS_HEIGHT;
    }

    private int rowsPerPage() {
        return HomeLayout.cellsThatFit(dotsTop() - gridStartY, appCellHeight(), PhoneTheme.APP_ROWS);
    }

    private int pageSize() {
        return columns() * rowsPerPage();
    }

    private int pageCount() {
        return HomeLayout.pageCount(entryCount(), pageSize());
    }

    /** 翻到第几页，真的换页了才 true */
    private boolean goToPage(int page) {
        int target = HomeLayout.clampPage(page, entryCount(), pageSize());
        if (target == homePage) return false;

        slideFromPage = homePage;
        homePage = target;

        // 开机动画里也照滑。原来这里挡着不滑，理由是"裁剪矩形按屏幕坐标算，与缩放中的
        // 手机对不上"——那个毛病 1.9.3 已经修掉了（GuiUtil.enableScissor 把矩形过一遍
        // pose 矩阵，开机动画那层缩放也在矩阵里）。留着这条限制的话，下一个人读到会以为
        // 裁剪还是坏的，然后照着再避一次不存在的坑
        pageSlideStartMs = System.currentTimeMillis();

        hoveredAppIndex = -1;
        preview = NO_DROP;
        return true;
    }

    private Position restingPosition(int index) {
        int slot = index % pageSize();
        return new Position(gridStartX + (slot % columns()) * appCellWidth(),
                gridStartY + (slot / columns()) * appCellHeight());
    }

    private Position displayedPosition(String id, int index) {
        Position resting = restingPosition(index);
        return index / pageSize() == reflowPage ? reflow.position(id, resting, reflowElapsedMs) : resting;
    }

    private Position dragPosition(double x, double y) {
        return new Position(x - grabX, y - grabY);
    }

    private Map<String, Position> pagePositions(int page, boolean displayed) {
        List<HomeArrangement.Entry> entries = PhoneScreenRegistry.homeEntries();
        Map<String, Position> positions = new HashMap<>();
        int start = page * pageSize();
        int end = Math.min(entries.size(), start + pageSize());
        for (int index = start; index < end; index++) {
            String id = entries.get(index).id();
            positions.put(id, displayed ? displayedPosition(id, index) : restingPosition(index));
        }
        return positions;
    }

    private HomeArrangement.Entry dragSource(int from) {
        return folderDrag == null ? entryAt(from) : new HomeArrangement.App(folderDrag.appId());
    }

    private void commitDrop(String sourceId, int from, DropTarget drop, Position ghost) {
        if (drop.groupId() == null && drop.insertion() == null) return;
        Map<String, Position> previous = pagePositions(homePage, true);
        int destination;
        Map<String, String> formerIds = Map.of();
        if (folderDrag != null) {
            int boundary = drop.insertion() == null ? -1 : drop.insertion().index();
            destination = PhoneScreenRegistry.dropFolderApp(folderDrag.folderId(), sourceId, boundary, drop.groupId(),
                    Component.translatable("mcphone.home.folder.default").getString());
            if (destination < 0) return;
            if (drop.groupId() == null) previous.put(sourceId, ghost);
            else formerIds = Map.of(entryAt(destination).id(), drop.groupId());
        } else if (drop.groupId() != null) {
            int targetIndex = indexOf(drop.groupId());
            if (!PhoneScreenRegistry.groupHomeApps(sourceId, drop.groupId(),
                    Component.translatable("mcphone.home.folder.default").getString())) return;
            destination = from < targetIndex ? targetIndex - 1 : targetIndex;
            formerIds = Map.of(entryAt(destination).id(), drop.groupId());
        } else {
            destination = drop.insertion().index();
            if (!PhoneScreenRegistry.moveHomeEntry(sourceId, destination)) return;
            // 松手后让实心图标沿着拖影的位置继续滑入目标格。
            previous.put(sourceId, ghost);
        }
        homePage = destination / pageSize();
        pageSlideStartMs = 0;
        hoveredAppIndex = -1;
        reflowPage = homePage;
        reflow = HomeReflow.between(previous, pagePositions(homePage, false), formerIds);
        reflowElapsedMs = 0;
    }

    /** 图标下面那行名字。可用宽度是格子步距而不是图标宽 */
    private void drawAppName(GuiGraphics g, String name, int ix, int iy, int is) {
        GuiUtil.drawIconLabel(g, font, name, ix, iy, is,
                is + PhoneTheme.APP_GRID_SPACING_X,
                PhoneTheme.APP_NAME_SCALE, FontPalette.appName());
    }

    private int hitEntry(double x, double y, boolean iconOnly) {
        return hitEntry(x, y, iconOnly, -1);
    }

    private int hitEntry(double x, double y, boolean iconOnly, int excluded) {
        if (slideProgress() < 1f || !insideGridArea(x, y)) return -1;
        List<HomeArrangement.Entry> entries = PhoneScreenRegistry.homeEntries();
        int size = PhoneTheme.APP_ICON_SIZE;
        int start = homePage * pageSize();
        int end = Math.min(entries.size(), start + pageSize());
        int labelHeight = iconOnly ? 0 : appCellHeight() - size;
        int labelHit = -1;
        // 反过来查找，重叠时先命中盖在上面的图标。图案优先于另一格的名字。
        for (int index = end - 1; index >= start; index--) {
            if (index == excluded) continue;
            Position position = displayedPosition(entries.get(index).id(), index);
            if (hitsIcon(x, y, position, 0)) return index;
            if (labelHit < 0 && labelHeight > 0 && x >= position.x() && x < position.x() + size
                    && y >= position.y() + size && y < position.y() + size + labelHeight) labelHit = index;
        }
        return labelHit;
    }

    private boolean insideGridArea(double x, double y) {
        return x >= phoneLeft && x < phoneLeft + metrics.contentWidth()
                && y >= phoneTop + PhoneTheme.STATUS_BAR_HEIGHT && y < dotsTop();
    }

    private DropTarget dropAt(HomeArrangement.Entry source, int from, double pointerX, double pointerY, Position ghost) {
        if (!insideGridArea(pointerX, pointerY) || slideProgress() < 1f) return NO_DROP;
        // 即使图标中心碰到最外一列，鼠标贴边时也有一条专门触发翻页的区域。
        if (pageEdgeSide(pointerX, PAGE_EDGE_GUTTER) != 0) return NO_DROP;
        int halfSize = PhoneTheme.APP_ICON_SIZE / 2;
        double x = ghost.x() + halfSize;
        double y = ghost.y() + halfSize;
        if (source instanceof HomeArrangement.App) {
            int previous = indexOf(preview.groupId());
            if (previous >= 0 && previous != from && previous / pageSize() == homePage
                    && hitsIcon(x, y, displayedPosition(preview.groupId(), previous), MERGE_EXIT_MARGIN)) {
                return preview;
            }
            int target = hitEntry(x, y, true, from);
            if (target >= 0) {
                String targetId = entryAt(target).id();
                return folderDrag != null && folderDrag.folderId().equals(targetId)
                        ? NO_DROP : new DropTarget(targetId, null);
            }
        }
        HomeLayout.Insertion insertion = insertionAt(x, y, from);
        return insertion == null ? NO_DROP : new DropTarget(null, insertion);
    }

    private HomeLayout.Insertion insertionAt(double x, double y, int from) {
        int size = PhoneTheme.APP_ICON_SIZE;
        if (reflow.isEmpty() || reflowPage != homePage) {
            return HomeLayout.insertionAt(x, y, gridStartX, gridStartY, appCellWidth(), appCellHeight(),
                    size, columns(), rowsPerPage(), homePage, entryCount(), from);
        }
        // 刚补位就按住下一个 App 时，插入线也要以冻结在画面里的位置为准。
        int start = homePage * pageSize();
        int end = Math.min(entryCount(), start + pageSize());
        List<Position> positions = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) {
            positions.add(displayedPosition(entryAt(i).id(), i));
        }
        return HomeLayout.insertionAtDrawnPositions(x, y, size, appCellWidth(), start, positions, from);
    }

    private static boolean hitsIcon(double x, double y, Position position, double margin) {
        int size = PhoneTheme.APP_ICON_SIZE;
        return x >= position.x() - margin && x < position.x() + size + margin
                && y >= position.y() - margin && y < position.y() + size + margin;
    }

    private static int indexOf(String id) {
        if (id == null) return -1;
        List<HomeArrangement.Entry> entries = PhoneScreenRegistry.homeEntries();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private static int entryCount() {
        return PhoneScreenRegistry.homeEntries().size();
    }

    private static HomeArrangement.Entry entryAt(int index) {
        List<HomeArrangement.Entry> entries = PhoneScreenRegistry.homeEntries();
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }
}
