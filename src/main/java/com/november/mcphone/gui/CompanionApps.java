package com.november.mcphone.gui;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.IPhoneApp;
import com.november.mcphone.api.client.RequiredMod;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.client.PhoneTheme;

/**
 * 联动 App 那一页 —— 哪些 App 靠别的模组撑着，以及那些模组装没装。
 *
 * ============================================================
 * 这一页回答一个问题：我的手机还能多出什么
 * ============================================================
 *
 * 前置没装的 App 不进目录，主屏和商店的普通列表里都没有它——这是刻意的，
 * 商店里躺着一个点了必然报错的东西比它不存在更糟。但代价是玩家彻底看不到
 * 它，连"装了 MCEF 能多个浏览器"这件事都无从得知。
 *
 * 这一页就是那个代价的补偿：把它们连同缺什么一起列出来，看得见、点不动。
 *
 * ============================================================
 * 为什么是列表，不是和商店一样的图标网格
 * ============================================================
 *
 * 因为这一页的主角是那行"需要 XXX"，而不是图标。屏幕只有 120 像素宽，网格
 * 一行四个，每格连 App 名都放不全，更别说再塞一句前置说明。列表一行一个，
 * 右边有整整一行的地方写清楚。
 *
 * ============================================================
 * 读这些 App 的元数据必须兜住 Throwable
 * ============================================================
 *
 * 这一页列的 App 里，有一部分它依赖的模组正好不在。附属模组完全可能在
 * getDisplayName() 或 getIconTexture() 里碰对方的类——那抛的是
 * NoClassDefFoundError，属于 Error 不是 Exception。
 *
 * 接不住的话，玩家点开这一页整个手机界面就崩了，而这一页存在的意义只是
 * 告诉他缺个模组。代价完全不成比例，所以每一处读取都单独兜。
 */
public final class CompanionApps {

    private static final int PAD = 6;

    /** 图标比主屏小一圈：这里一行一个，不需要那么大，省下的宽度给文字 */
    private static final int ICON = 16;

    /** 图标与文字之间的空隙 */
    private static final int GAP = 4;

    /** 一行占多高：图标 16，两行字 9+9=18，取大的那个再加点空 */
    private static final int ROW_H = 21;

    /** 翻页条高度。只有真的多于一页时才占这块地方 */
    private static final int PAGER_H = 12;

    private final List<Row> rows = new ArrayList<>();

    private boolean requested = false;
    private int page = 0;
    private int rowsPerPage = 1;
    private int pagerY;
    private boolean hoverPrev = false;
    private boolean hoverNext = false;

    /**
     * 一行要画的东西，在刷新时一次算好。
     *
     * 不在渲染里现算是因为每一项都要兜 Throwable——每帧兜一次异常，等于把
     * 一个可能反复抛错的调用放进渲染热路径，日志会被刷爆。
     *
     * @param name      App 名字
     * @param icon      图标，可能为 null
     * @param requires  "需要 Waystones（传送石碑）" 这一行
     * @param satisfied 前置齐了没有
     */
    private record Row(String name, ResourceLocation icon, String requires, boolean satisfied) {}

    /** 进这一页时调用，重新扫一遍 */
    public void refresh() {
        requested = true;
        rows.clear();

        for (IPhoneApp app : PhoneScreenRegistry.getCompanionApps()) {
            List<RequiredMod> required = PhoneScreenRegistry.requiredModsOf(app);
            if (required.isEmpty()) continue;

            boolean satisfied = true;
            StringBuilder names = new StringBuilder();
            for (RequiredMod mod : required) {
                if (!names.isEmpty()) names.append("、");
                names.append(mod.displayName());
                if (!ModList.get().isLoaded(mod.modId())) satisfied = false;
            }

            rows.add(new Row(
                    readName(app),
                    readIcon(app),
                    Component.translatable("mcphone.store.companion.requires",
                            names.toString()).getString(),
                    satisfied));
        }

        clampPage();
    }

    /** 离开这一页时调用 */
    public void reset() {
        requested = false;
        page = 0;
        hoverPrev = false;
        hoverNext = false;
    }

    /** 读 App 名字，读不出来就用它的 id 顶上——总比整页崩掉强 */
    private static String readName(IPhoneApp app) {
        try {
            Component name = app.getDisplayName();
            if (name != null) return name.getString();
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 读取 {} 的名字失败，用 id 代替",
                    app.getClass().getName(), t);
        }
        try {
            return app.getId().getPath();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 读图标，读不出来就当没有图标（画占位方块） */
    private static ResourceLocation readIcon(IPhoneApp app) {
        try {
            return app.getIconTexture();
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 读取 {} 的图标失败，画占位方块",
                    app.getClass().getName(), t);
            return null;
        }
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

        g.drawString(font, Component.translatable("mcphone.store.companion").getString(),
                x, y, PhoneTheme.FONT_COLOR_TITLE, true);
        y += font.lineHeight + 4;

        g.fill(x, y, x + w, y + 1, 0x44FFFFFF);
        y += 4;

        if (rows.isEmpty()) {
            g.drawString(font, Component.translatable("mcphone.store.companion.empty").getString(),
                    x, y, PhoneTheme.FONT_COLOR_SUBTLE, false);
            return;
        }

        // 先按"要不要翻页条"留出下边界，与商店那边同样的保守算法：
        // 宁可少放一行，也不能让最后一行被翻页条压住
        int listBottom = bottom - PAGER_H - 2;
        rowsPerPage = Math.max(1, (listBottom - y) / ROW_H);
        pagerY = bottom - PAGER_H;

        clampPage();

        int from = page * rowsPerPage;
        int to = Math.min(rows.size(), from + rowsPerPage);

        for (int i = from; i < to; i++) {
            drawRow(g, font, rows.get(i), x, y + (i - from) * ROW_H, w);
        }

        renderPager(g, font, x, w, mouseX, mouseY);
    }

    /**
     * 一行：图标 + 名字 + 「需要 XXX」。
     *
     * 前置没齐的那些整行压暗，包括名字。只把"未装"两个字标成灰的话，玩家一眼
     * 扫过去看到的还是一排一模一样的 App，得逐行读文字才分得出哪个能用。
     */
    private void drawRow(GuiGraphics g, Font font, Row row, int x, int y, int w) {
        // 图标。没有贴图的画占位方块，而不是让 blit 去画一张不存在的贴图
        if (row.icon() != null) {
            g.blit(row.icon(), x, y, 0, 0, ICON, ICON, ICON, ICON);
        } else {
            g.fill(x, y, x + ICON, y + ICON, PhoneTheme.COLOR_BUTTON_DISABLED);
        }

        int textX = x + ICON + GAP;
        int textW = w - ICON - GAP;

        // 已装/未装靠右，与关于页用同一对文案。用文字而不是 ✓ ✗：
        // 那两个符号在部分字体下会掉成方框
        String state = Component.translatable(
                row.satisfied() ? "mcphone.about.installed" : "mcphone.about.missing").getString();
        int stateW = font.width(state);

        // 名字要给状态让出位置。不减这一块的话，长名字会直接压在"未装"上面，
        // 而这一页最要紧的信息恰恰就是那两个字
        g.drawString(font, clip(font, row.name(), textW - stateW - GAP), textX, y,
                row.satisfied() ? PhoneTheme.FONT_COLOR_BODY
                        : PhoneTheme.FONT_COLOR_BUTTON_DISABLED, false);

        // 第二行没有状态文字挡着，可以用满整行宽度
        g.drawString(font, clip(font, row.requires(), textW), textX, y + font.lineHeight + 1,
                PhoneTheme.FONT_COLOR_SUBTLE, false);

        g.drawString(font, state, x + w - stateW, y,
                row.satisfied() ? PhoneTheme.COLOR_BUTTON_HOVER : PhoneTheme.FONT_COLOR_SUBTLE,
                false);
    }

    /** 放不下就截断加省略号。整合包里的模组名可以很长 */
    private static String clip(Font font, String text, int maxW) {
        if (font.width(text) <= maxW) return text;
        return font.plainSubstrByWidth(text, maxW - font.width("…")) + "…";
    }

    private int pageCount() {
        return Math.max(1, (rows.size() + rowsPerPage - 1) / rowsPerPage);
    }

    private void clampPage() {
        if (page >= pageCount()) page = pageCount() - 1;
        if (page < 0) page = 0;
    }

    /** 翻页条。与商店首页同一套写法，只有一页时整块不画 */
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

    /**
     * 这一页只有翻页可点。
     *
     * 行本身刻意不可点：能用的那些去商店正常下载，用不了的点了也没有任何
     * 能做的事——弹一句"请去装 MCEF"不比那行已经写着的"需要 MCEF · 未装"
     * 多说什么。
     */
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
        return false;
    }
}
