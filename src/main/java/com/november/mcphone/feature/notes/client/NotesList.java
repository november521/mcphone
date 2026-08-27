package com.november.mcphone.feature.notes.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.feature.notes.NotePrinter;
import com.november.mcphone.feature.notes.NoteSummary;
import com.november.mcphone.feature.notes.net.NotesClientCache;
import com.november.mcphone.feature.notes.net.PrintNotePacket;
import com.november.mcphone.feature.notes.net.RequestNoteListPacket;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import com.november.mcphone.core.net.MCphoneNetwork;

import java.util.List;

/** 笔记列表页。数据来自 {@link NotesClientCache}；不轮询，进来拉一次，改完服务端会主动回发 */
public final class NotesList {

    private static final int PAD = 4;

    private static int colorTitle() { return FontPalette.title(); }
    private static int colorPreview() { return FontPalette.preview(); }
    private static int colorTime() { return FontPalette.timestamp(); }
    private static final int COLOR_ROW_HOVER = PhoneTheme.COLOR_ROW_HOVER;

    private int scrollOffset;
    private int hoveredIdx = -1;
    private boolean addHovered;

    /** 悬停在哪一条的「打印」上，-1 表示没有；与 hoveredIdx 区域重叠，点击时必须先问它 */
    private int printHoveredIdx = -1;

    /** 屏幕内的一行临时提示：动作栏在机身之外，玩家看不见 */
    private String toast = "";
    private long toastUntilMs;

    private static final long TOAST_MS = 2500L;

    private static int colorToast() { return FontPalette.armed(); }

    private static int colorPrint() { return FontPalette.confirm(); }

    /** 待消费的"打开某条"请求，null 表示没有 */
    private Integer pendingOpen;

    private boolean pendingNew;

    public void open() {
        scrollOffset = 0;
        hoveredIdx = -1;
        printHoveredIdx = -1;
        toast = "";
        pendingOpen = null;
        pendingNew = false;
        MCphoneNetwork.sendToServer(new RequestNoteListPacket());
    }

    public void close() {
        hoveredIdx = -1;
        printHoveredIdx = -1;
        addHovered = false;
    }

    public Integer consumeOpenRequest() {
        Integer out = pendingOpen;
        pendingOpen = null;
        return out;
    }

    public boolean consumeNewRequest() {
        boolean out = pendingNew;
        pendingNew = false;
        return out;
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;
        final int bottom = phoneTop + screenH - navH;
        int y = phoneTop + statusH + 4;

        y = renderHeader(g, font, x, y, w, mouseX, mouseY);

        List<NoteSummary> list = NotesClientCache.getSummaries();
        if (list.isEmpty()) {
            renderEmpty(g, font, x, y, w);
            hoveredIdx = -1;
            printHoveredIdx = -1;
            return;
        }

        clampScroll(list.size(), bottom - y, font);
        renderRows(g, font, list, x, y, w, bottom, mouseX, mouseY);

        renderToast(g, font, x, bottom - font.lineHeight, w);
    }

    private int renderHeader(GuiGraphics g, Font font, int x, int y, int w,
                             int mouseX, int mouseY) {
        g.drawString(font, Component.translatable("mcphone.app.notes").getString(),
                x, y, FontPalette.title(), true);

        String plus = "+";
        int plusW = font.width(plus);
        int plusX = x + w - plusW - 2;
        addHovered = mouseX >= plusX - 3 && mouseX <= plusX + plusW + 3
                  && mouseY >= y - 2 && mouseY <= y + font.lineHeight + 2;
        g.drawString(font, plus, plusX, y, addHovered ? FontPalette.title() : FontPalette.link(), true);

        y += font.lineHeight + 4;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        return y + 4;
    }

    private void renderEmpty(GuiGraphics g, Font font, int x, int y, int w) {
        g.drawString(font, Component.translatable("mcphone.notes.empty").getString(),
                x, y, FontPalette.subtle(), false);
        y += font.lineHeight + 2;
        for (var line : font.split(Component.translatable("mcphone.notes.empty_hint"), w)) {
            g.drawString(font, line, x, y, FontPalette.dim(), false);
            y += font.lineHeight;
        }
    }

    private void renderRows(GuiGraphics g, Font font, List<NoteSummary> list,
                            int x, int y, int w, int bottom, int mouseX, int mouseY) {
        final int rowH = rowHeight(font);
        final String print = Component.translatable("mcphone.notes.print").getString();
        hoveredIdx = -1;
        printHoveredIdx = -1;

        for (int i = scrollOffset; i < list.size(); i++) {
            if (y + rowH > bottom) break;

            NoteSummary note = list.get(i);
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + rowH;
            if (hovered) {
                hoveredIdx = i;
                g.fill(x, y, x + w, y + rowH, COLOR_ROW_HOVER);
            }

            String time = GuiUtil.formatTime(note.modified());
            int timeW = font.width(time);
            g.drawString(font, time, x + w - timeW, y, colorTime(), false);

            String title = note.title().isEmpty()
                    ? Component.translatable("mcphone.notes.untitled").getString()
                    : note.title();
            g.drawString(font, GuiUtil.truncate(font, title, w - timeW - 4), x, y, colorTitle(), false);

            int printW = font.width(print);
            int printX = x + w - printW;
            boolean printHover = mouseX >= printX - 2 && mouseX <= x + w
                    && mouseY >= y + font.lineHeight && mouseY < y + rowH;
            if (printHover) printHoveredIdx = i;

            g.drawString(font, GuiUtil.truncate(font, note.preview(), w - printW - 4),
                    x, y + font.lineHeight + 1, colorPreview(), false);

            // 每一行都画「打印」：只在悬停行画会让预览文字跳动
            g.drawString(font, print, printX, y + font.lineHeight + 1,
                    printHover ? colorTitle() : colorPrint(), false);

            y += rowH;
        }
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        if (addHovered) {
            pendingNew = true;
            return true;
        }

        List<NoteSummary> list = NotesClientCache.getSummaries();

        // 打印区域与整行重叠，必须先判打印
        if (printHoveredIdx >= 0 && printHoveredIdx < list.size()) {
            print(list.get(printHoveredIdx).id());
            return true;
        }

        if (hoveredIdx >= 0 && hoveredIdx < list.size()) {
            pendingOpen = list.get(hoveredIdx).id();
            return true;
        }
        return false;
    }

    /** 只发 id，正文以服务端那份为准；不跳转 */
    private void print(int id) {
        // 缺书在客户端就能判定，不必等服务端拒绝
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !NotePrinter.COST.canAfford(mc.player)) {
            showToast("mcphone.notes.print_failed");
            return;
        }

        MCphoneNetwork.sendToServer(new PrintNotePacket(id));
        showToast("mcphone.notes.print_done");
    }

    private void showToast(String translationKey) {
        toast = Component.translatable(translationKey).getString();
        toastUntilMs = System.currentTimeMillis() + TOAST_MS;
    }

    private void renderToast(GuiGraphics g, Font font, int x, int y, int w) {
        if (toast.isEmpty() || System.currentTimeMillis() > toastUntilMs) return;
        int tw = font.width(toast);
        g.drawString(font, toast, x + (w - tw) / 2, y, colorToast(), false);
    }

    public boolean mouseScrolled(double scrollY) {
        if (scrollY > 0 && scrollOffset > 0) {
            scrollOffset--;
            return true;
        }
        if (scrollY < 0 && scrollOffset < NotesClientCache.getSummaries().size() - 1) {
            scrollOffset++;
            return true;
        }
        return false;
    }

    private static int rowHeight(Font font) {
        return font.lineHeight * 2 + 4;
    }

    /** 列表变短时（删了几条）必须夹紧，否则会滚到空白处 */
    private void clampScroll(int total, int availableHeight, Font font) {
        int visible = Math.max(1, availableHeight / rowHeight(font));
        int maxOffset = Math.max(0, total - visible);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
        if (scrollOffset < 0) scrollOffset = 0;
    }

}
