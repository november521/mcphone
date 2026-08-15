package com.november.mcphone.feature.store.client;

import com.november.mcphone.api.client.store.AppInfo;
import com.november.mcphone.api.client.store.IAppSource;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.feature.store.client.AppSourceRegistry;
import com.november.mcphone.feature.store.net.StoreClientCache;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用商店首页 —— 可下载 App 的图标网格。
 *
 * ============================================================
 * 为什么画成网格，而不是原来那种一行一个的列表
 * ============================================================
 *
 * 因为这是手机。手机上的应用商店从来不是一列文字，而且屏幕只有 120 像素
 * 宽——一行文字加一个"安装"按钮已经把宽度占满，App 名字稍长就得截断成
 * "便携末影…"。网格里名字在图标下面，能放下的字反而更多。
 *
 * 网格的排布参数与主屏共用 {@link PhoneTheme} 里那一套：同一部手机上
 * 两处图标间距不一样，看着就是没做完。
 *
 * ============================================================
 * 翻页现在用不上，但骨架留着
 * ============================================================
 *
 * 一屏放得下 4×4=16 个，而现在总共才 9 个 App，翻页条永远不会出现——
 * {@link #pageCount()} 为 1 时整块不画，不占那 12 像素。
 *
 * 留着骨架是因为分页这件事的麻烦不在于画两个箭头，而在于"当前页"这个状态
 * 要贯穿渲染、命中判定、以及列表刷新后的越界修正。等真装了几十个 App 才来
 * 补，就得回头把这三处都改一遍，而那时候还要重新想清楚一遍。
 *
 * ============================================================
 * 本类只提出请求，不决定去哪个界面
 * ============================================================
 *
 * 点了某个 App 只记下 {@link #consumeOpenRequest()}，由 PhoneScreen 决定
 * 要不要跳详情页。组件不该知道 PhoneScreen 的导航结构——聊天列表与记事本
 * 列表都是这么做的。
 */
public final class AppStore {

    private static final int PAD = 6;

    /** 翻页条高度。只有真的多于一页时才占这块地方 */
    private static final int PAGER_H = 12;

    private final List<AppInfo> available = new ArrayList<>();

    /** 是否已发起过一次拉取。避免每帧都请求各来源 */
    private boolean requested = false;

    /** 当前第几页，从 0 起 */
    private int page = 0;

    private int hoveredIdx = -1;
    private boolean hoverPrev = false;
    private boolean hoverNext = false;

    /** 玩家点开了哪个 App，等 PhoneScreen 来取 */
    private AppInfo openRequest = null;

    /**
     * 玩家点了「联动 App」那个格子，等 PhoneScreen 来取。
     *
     * 与 openRequest 一样只是个请求：本类不知道 PhoneScreen 的导航结构，
     * 去哪个界面由那边决定。
     */
    private boolean companionRequest = false;

    /** 有没有联动 App。没有就不画那个入口格子 */
    private boolean hasCompanion = false;

    /** 错误提示，显示在标题下方 */
    private Component message = null;

    // 上一帧算出来的网格几何。命中判定要用同一套数字，重算一遍迟早会算歪
    private int gridX, gridY, cellW, cellH, rowsPerPage;
    private int pagerY;

    /**
     * 重新拉取可安装列表，并向服务端要一份最新的购买记录。
     *
     * 购买记录必须每次进商店都问：它存在服务端，客户端这份只是镜像，
     * 而玩家可能在另一台设备上买过东西。
     */
    public void refresh() {
        requested = true;
        StoreClientCache.request();

        // 一个联动 App 都没有时不画那个入口格子。空手点进去看到一句"没有联动
        // App"，比压根没有这个入口更让人费解
        hasCompanion = !PhoneScreenRegistry.getCompanionApps().isEmpty();

        AppSourceRegistry.listAllAvailable(list -> {
            available.clear();
            if (list != null) available.addAll(list);
            clampPage();
        });
    }

    /** 离开商店界面时调用，下次进入重新拉取并清掉上次的提示 */
    public void reset() {
        requested = false;
        message = null;
        hoveredIdx = -1;
        page = 0;
        openRequest = null;
        companionRequest = false;
    }

    /** 取走"打开某个 App 详情"的请求，取走即清空 */
    public AppInfo consumeOpenRequest() {
        AppInfo r = openRequest;
        openRequest = null;
        return r;
    }

    /** 取走"打开联动 App 那一页"的请求，取走即清空 */
    public boolean consumeCompanionRequest() {
        boolean r = companionRequest;
        companionRequest = false;
        return r;
    }

    /** 安装成功后由详情页回调，用来把它从可下载列表里去掉 */
    public void onInstalled() {
        refresh();
    }

    // ============================================================
    //  分页
    // ============================================================

    private int perPage() {
        return PhoneTheme.APP_COLUMNS * Math.max(1, rowsPerPage);
    }

    private int pageCount() {
        int per = perPage();
        return Math.max(1, (cellCount() + per - 1) / per);
    }

    // ============================================================
    //  格子索引 —— 「联动 App」入口永远在最后一格
    // ============================================================
    //
    // 网格里画的东西不再与 available 一一对应：可下载的 App 占 0 到 size-1，
    // 联动入口接在它们后面。分页、命中判定全都走这套合并后的索引，只在真要取
    // AppInfo 时才换算回去——换算是恒等的，但别把这两个概念合并，加第二个
    // 特殊格子时就得重新拆开。
    //
    // 排在末尾是有道理的：商店首页第一眼该是可以下载的东西，而联动那一页说的是
    // "还能有什么"——它不是一个 App，是一份说明，本来就该排在正事后面。1.2.5
    // 曾经放在第 0 格，理由是末尾会随列表增减挪位置；但那个代价小于让说明挤在
    // 玩家真正要找的东西前面。

    private int cellCount() {
        return available.size() + (hasCompanion ? 1 : 0);
    }

    private boolean isCompanionCell(int cell) {
        return hasCompanion && cell == available.size();
    }

    /**
     * 格子索引 → available 的下标。
     *
     * 联动入口排在末尾，所以两者恰好相等。留着这个方法是为了让"格子索引"和
     * "App 下标"在代码里仍然是两个概念——它们只是当前碰巧相等，不是同一个东西。
     */
    private int appIndex(int cell) {
        return cell;
    }

    /**
     * 把页码拉回有效范围。
     *
     * 列表刷新后会变短——买完装上的那个 App 就从可下载列表里消失了。停在
     * 最后一页时这会让页码越界，界面变成空白，而玩家只会觉得"商店坏了"。
     */
    private void clampPage() {
        if (page >= pageCount()) page = pageCount() - 1;
        if (page < 0) page = 0;
    }

    // ============================================================
    //  渲染
    // ============================================================

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        if (!requested) refresh();

        int x = phoneLeft + PAD;
        int y = phoneTop + statusH + 4;
        int w = screenW - PAD * 2;
        int bottom = phoneTop + screenH - navH;

        g.drawString(font, Component.translatable("mcphone.app.app_store").getString(),
                x, y, PhoneTheme.FONT_COLOR_TITLE, true);
        y += font.lineHeight + 4;

        g.fill(x, y, x + w, y + 1, 0x44FFFFFF);
        y += 4;

        if (message != null) {
            String msg = message.getString();
            if (font.width(msg) > w - 4) msg = font.plainSubstrByWidth(msg, w - 8) + "…";
            g.drawString(font, msg, x, y, 0xFFFFAA44, false);
            y += font.lineHeight + 2;
        }

        if (cellCount() == 0) {
            g.drawString(font, Component.translatable("mcphone.store.empty").getString(),
                    x, y, PhoneTheme.FONT_COLOR_SUBTLE, false);
            hoveredIdx = -1;
            return;
        }

        // ---- 网格几何。命中判定共用这几个字段，别在别处重算 ----
        final int is = PhoneTheme.APP_ICON_SIZE;
        cellW = is + PhoneTheme.APP_GRID_SPACING_X;
        cellH = is + (int) (font.lineHeight * PhoneTheme.APP_NAME_SCALE) + 6;
        gridX = phoneLeft + PhoneTheme.APP_GRID_PADDING_LEFT;
        gridY = y + 2;

        // 先按"要不要翻页条"留出下边界。多算一次是为了避免鸡生蛋：
        // 能放几行取决于有没有翻页条，而有没有翻页条取决于能放几行。
        // 先按有翻页条算行数，是保守的一侧——宁可少放一行，也不能让最后
        // 一行被翻页条压住
        int gridBottom = bottom - PAGER_H - 2;
        rowsPerPage = Math.max(1, (gridBottom - gridY) / cellH);
        pagerY = bottom - PAGER_H;

        clampPage();

        int per = perPage();
        int from = page * per;
        int to = Math.min(cellCount(), from + per);

        hoveredIdx = -1;
        for (int i = from; i < to; i++) {
            int slot = i - from;
            int ix = gridX + (slot % PhoneTheme.APP_COLUMNS) * cellW;
            int iy = gridY + (slot / PhoneTheme.APP_COLUMNS) * cellH;

            boolean hovered = mouseX >= ix && mouseX <= ix + is
                    && mouseY >= iy && mouseY <= iy + is;
            if (hovered) {
                hoveredIdx = i;
                g.fill(ix - 2, iy - 2, ix + is + 2, iy + is + 2, PhoneTheme.COLOR_APP_PRESSED);
            }

            if (isCompanionCell(i)) {
                drawCompanionCell(g, font, ix, iy, is);
                continue;
            }

            AppInfo info = available.get(appIndex(i));
            drawIcon(g, info, ix, iy, is);
            drawName(g, font, info.displayName().getString(), ix, iy, is);
        }

        renderPager(g, font, x, w, mouseX, mouseY);
    }

    /**
     * 画图标。
     *
     * AppInfo 的图标可以为 null——远程来源列出的 App 本地还没有实现，
     * 自然也没有贴图。那时画一个占位方块，而不是让 blit 去画一张不存在的
     * 贴图（那会得到紫黑格）。
     */
    private void drawIcon(GuiGraphics g, AppInfo info, int x, int y, int size) {
        if (info.iconTexture() != null) {
            g.blit(info.iconTexture(), x, y, 0, 0, size, size, size, size);
        } else {
            g.fill(x, y, x + size, y + size, PhoneTheme.COLOR_BUTTON_DISABLED);
        }
    }

    /**
     * 「联动 App」那个入口格子。排在所有可下载 App 之后。
     *
     * 贴图优先、纯色兜底：没放 store_companion.png 时画一个纯色底加三个小方块，
     * 暗示"这里头装着好几个 App"。不画字符——好看的符号在部分字体下会掉成方框，
     * 而这是玩家进商店第一眼看到的格子，理由见 PhoneSkin.Element.STORE_COMPANION。
     */
    private void drawCompanionCell(GuiGraphics g, Font font, int x, int y, int size) {
        if (!PhoneSkin.draw(g, PhoneSkin.Element.STORE_COMPANION, x, y, size, size)) {
            g.fill(x, y, x + size, y + size, PhoneTheme.COLOR_STATUS_BAR);

            int s = Math.max(2, size / 6);
            int gap = Math.max(1, s / 2);
            int total = s * 3 + gap * 2;
            int bx = x + (size - total) / 2;
            int by = y + (size - s) / 2;
            for (int i = 0; i < 3; i++) {
                int sx = bx + i * (s + gap);
                g.fill(sx, by, sx + s, by + s, PhoneTheme.FONT_COLOR_SUBTLE);
            }
        }
        drawName(g, font, Component.translatable("mcphone.store.companion").getString(),
                x, y, size);
    }

    /**
     * 图标下面那行名字。
     *
     * 变换的写法与主屏的 drawAppName 一致：先 translate 到目标位置再 scale，
     * 最后在原点画。缩放一个非原点坐标会让名字越长偏得越多——1.0.42 修过
     * 一次，别在这里重蹈覆辙。
     */
    private void drawName(GuiGraphics g, Font font, String name, int ix, int iy, int is) {
        float ns = PhoneTheme.APP_NAME_SCALE;
        float nw = font.width(name) * ns;

        g.pose().pushPose();
        g.pose().translate(ix + (is - nw) / 2f, iy + is + 2, 0);
        g.pose().scale(ns, ns, 1f);
        g.drawString(font, name, 0, 0, PhoneTheme.FONT_COLOR_APP_NAME, false);
        g.pose().popPose();
    }

    /** 翻页条。只有一页时整块不画 */
    private void renderPager(GuiGraphics g, Font font, int x, int w, int mouseX, int mouseY) {
        hoverPrev = false;
        hoverNext = false;

        int pages = pageCount();
        if (pages <= 1) return;

        String prev = "◀";
        String next = "▶";
        String label = (page + 1) + " / " + pages;

        int prevX = x;
        int nextX = x + w - font.width(next);
        int labelX = x + (w - font.width(label)) / 2;

        boolean canPrev = page > 0;
        boolean canNext = page < pages - 1;

        hoverPrev = canPrev && hit(mouseX, mouseY, prevX, pagerY, font.width(prev), font.lineHeight);
        hoverNext = canNext && hit(mouseX, mouseY, nextX, pagerY, font.width(next), font.lineHeight);

        g.drawString(font, prev, prevX, pagerY,
                canPrev ? (hoverPrev ? PhoneTheme.FONT_COLOR_TITLE : PhoneTheme.FONT_COLOR_BODY)
                        : PhoneTheme.COLOR_BUTTON_DISABLED, false);
        g.drawString(font, next, nextX, pagerY,
                canNext ? (hoverNext ? PhoneTheme.FONT_COLOR_TITLE : PhoneTheme.FONT_COLOR_BODY)
                        : PhoneTheme.COLOR_BUTTON_DISABLED, false);
        g.drawString(font, label, labelX, pagerY, PhoneTheme.FONT_COLOR_SUBTLE, false);
    }

    private static boolean hit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    // ============================================================
    //  鼠标
    // ============================================================

    public boolean mouseClicked(double mx, double my, int button) {
        if (hoverPrev) {
            page--;
            clampPage();
            return true;
        }
        if (hoverNext) {
            page++;
            clampPage();
            return true;
        }

        if (hoveredIdx < 0 || hoveredIdx >= cellCount()) return false;

        // 联动入口只提请求，去哪个界面由 PhoneScreen 决定
        if (isCompanionCell(hoveredIdx)) {
            companionRequest = true;
            return true;
        }

        AppInfo info = available.get(appIndex(hoveredIdx));
        IAppSource source = AppSourceRegistry.getSource(info.sourceId());
        if (source == null) {
            // 来源都找不到就别放人进详情页了：那边的"下载"按钮点下去
            // 同样会失败，不如在这里就说清楚
            message = Component.translatable("mcphone.store.error.no_source",
                    info.sourceId().toString());
            return true;
        }

        openRequest = info;
        return true;
    }
}
