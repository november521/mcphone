package com.november.mcphone.feature.store.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * 联动 App 页：列出靠别的模组撑着的 App 及其前置装没装（前置没装的 App
 * 不进目录，玩家只在这里看得到）。读这些 App 的元数据必须逐处兜住
 * Throwable：依赖缺失时抛的是 NoClassDefFoundError（Error），不兜整页就崩。
 */
public final class CompanionApps {

    private static final int PAD = 6;

    private static final int ICON = 16;

    private static final int GAP = 4;

    private static final int ROW_H = 21;

    private static final int PAGER_H = 12;

    private final List<Row> rows = new ArrayList<>();

    private boolean requested = false;
    private int page = 0;
    private int rowsPerPage = 1;
    private int pagerY;
    private boolean hoverPrev = false;
    private boolean hoverNext = false;

    /** 一行要画的东西，刷新时一次算好（可能抛错的调用不进渲染热路径）；icon 可为 null */
    private record Row(String name, ResourceLocation icon, String requires, boolean satisfied) {}

    /** 进这一页时调用，重新扫一遍 */
    public void refresh() {
        requested = true;
        rows.clear();

        for (IPhoneApp app : PhoneScreenRegistry.getCompanionApps()) {
            List<RequiredMod> required = PhoneScreenRegistry.requiredModsOf(app);

            // 只声明了【联动】的，也要在这一页说得出自己缺什么。
            //
            // 走到这一句时 required 是空的，说明这个 App 必定来自 UNAVAILABLE——
            // getCompanionApps() 的另一支是按"前置非空"筛的，筛得出来就不会空。
            // 也就是说这条回退只会碰到【当前不可用】的 App，不会把一个正常能用的
            // App 拉进这一页。
            //
            // 不回退的话，一个"靠某个模组撑着、但声明成联动"的 App 在对方没装时
            // 会从玩家眼前彻底消失：主屏没有、商店没有、这一页也没有。而这一页
            // 存在的全部理由就是回答"我怎么没有这个"
            if (required.isEmpty()) required = PhoneScreenRegistry.companionModsOf(app);
            if (required.isEmpty()) continue;

            boolean satisfied = true;
            StringBuilder names = new StringBuilder();
            for (RequiredMod mod : required) {
                if (!names.isEmpty()) names.append("、");
                names.append(mod.displayName());
                if (!ModList.get().isLoaded(mod.modId())) satisfied = false;
            }

            // 已经可用、却不是每个都装齐的，不画。
            //
            // 这是「阅读」那种"装了任一个就有内容"的 App：只装了 Patchouli 时它好好地
            // 待在主屏上，而这一页会把它画成一行压暗的"未装"，因为 GuideME 与沉浸工程
            // 没装。那一行是句假话——玩家看着自己正在用的 App 被标成用不了。
            //
            // 反过来"不可用"的一律要画，无论满足了几个：那正是这一页存在的理由。
            // 单模组撑着的 App（浏览器、传送石、任务书）可用与满足是同一件事，
            // 这一句碰不到它们
            if (satisfied || !PhoneScreenRegistry.isRegistered(app)) {
                rows.add(new Row(
                        readName(app),
                        readIcon(app),
                        Component.translatable("mcphone.store.companion.requires",
                                names.toString()).getString(),
                        satisfied));
            }
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

    /** 读不出来就用 id 顶上 */
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

    /** 读不出来就当没有图标 */
    private static ResourceLocation readIcon(IPhoneApp app) {
        try {
            return app.getIconTexture();
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 读取 {} 的图标失败，画占位方块",
                    app.getClass().getName(), t);
            return null;
        }
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        if (!requested) refresh();

        int x = phoneLeft + PAD;
        int y = phoneTop + statusH + 4;
        int w = screenW - PAD * 2;
        int bottom = phoneTop + screenH - navH;

        g.drawString(font, Component.translatable("mcphone.store.companion").getString(),
                x, y, FontPalette.title(), true);
        y += font.lineHeight + 4;

        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 4;

        if (rows.isEmpty()) {
            g.drawString(font, Component.translatable("mcphone.store.companion.empty").getString(),
                    x, y, FontPalette.subtle(), false);
            return;
        }

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

    /** 一行：图标 + 名字 + 「需要 XXX」；前置没齐的整行压暗 */
    private void drawRow(GuiGraphics g, Font font, Row row, int x, int y, int w) {
        if (row.icon() != null) {
            GuiUtil.drawTexture(g, row.icon(), x, y, ICON, ICON, ICON, ICON);
        } else {
            g.fill(x, y, x + ICON, y + ICON, PhoneTheme.COLOR_BUTTON_DISABLED);
        }

        int textX = x + ICON + GAP;
        int textW = w - ICON - GAP;

        // 用文字不用 ✓ ✗：那两个符号在部分字体下会掉成方框
        String state = Component.translatable(
                row.satisfied() ? "mcphone.about.installed" : "mcphone.about.missing").getString();
        int stateW = font.width(state);

        // 名字给状态文字让位，否则长名字会压在"未装"上
        g.drawString(font, clip(font, row.name(), textW - stateW - GAP), textX, y,
                row.satisfied() ? FontPalette.body()
                        : FontPalette.muted(), false);

        g.drawString(font, clip(font, row.requires(), textW), textX, y + font.lineHeight + 1,
                FontPalette.subtle(), false);

        g.drawString(font, state, x + w - stateW, y,
                row.satisfied() ? FontPalette.confirm() : FontPalette.subtle(),
                false);
    }

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

    /** 只有一页时整块不画 */
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

        hoverPrev = canPrev && GuiUtil.hit(mouseX, mouseY, prevX, pagerY, font.width(prev), font.lineHeight);
        hoverNext = canNext && GuiUtil.hit(mouseX, mouseY, nextX, pagerY, font.width(next), font.lineHeight);

        g.drawString(font, prev, prevX, pagerY,
                canPrev ? (hoverPrev ? FontPalette.title() : FontPalette.body())
                        : FontPalette.muted(), false);
        g.drawString(font, next, nextX, pagerY,
                canNext ? (hoverNext ? FontPalette.title() : FontPalette.body())
                        : FontPalette.muted(), false);
        g.drawString(font, label, labelX, pagerY, FontPalette.subtle(), false);
    }

    /** 只有翻页可点，行本身刻意不可点 */
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
