package com.november.mcphone.feature.notes.client;

import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.feature.notes.NotePrinter;
import com.november.mcphone.feature.notes.NoteSummary;
import com.november.mcphone.feature.notes.net.NotesClientCache;
import com.november.mcphone.feature.notes.net.PrintNotePacket;
import com.november.mcphone.feature.notes.net.RequestNoteListPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 笔记列表 —— 打开记事本看到的第一屏。
 *
 * 数据全部来自 {@link NotesClientCache}，本类不持有真值。
 *
 * ============================================================
 * 为什么不定时刷新
 * ============================================================
 *
 * 聊天列表要每隔几秒拉一次，因为别人的上线下线与新消息不归自己控制。
 * 笔记只有自己会改，进来拉一次就够；改完之后服务端会主动回发新列表，
 * 界面自然跟着变。多轮询一次都是白费。
 */
public final class NotesList {

    private static final int PAD = 4;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM-dd");

    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_PREVIEW = 0xFF999999;
    private static final int COLOR_TIME = 0xFF777777;
    private static final int COLOR_ROW_HOVER = 0x33FFFFFF;

    private int scrollOffset;
    private int hoveredIdx = -1;
    private boolean addHovered;

    /**
     * 鼠标正悬在哪一条的「打印」上，-1 表示没有。
     *
     * 与 hoveredIdx 分开记：这两块区域是重叠的（打印就画在行里），点击时必须
     * 先问打印再问整行，否则点打印会变成打开笔记。
     */
    private int printHoveredIdx = -1;

    /**
     * 手机屏幕里的一行临时提示。
     *
     * 打印的结果本来是服务端用动作栏说的，但动作栏在游戏 HUD 上、手机机身之外
     * ——玩家正盯着手机屏幕，得先关掉手机才看得见那句话，等于白说。所以结果也在
     * 这块小屏幕里说一遍。
     */
    private String toast = "";
    private long toastUntilMs;

    /** 提示停留时长。够读完一行短句，又不至于赖着不走 */
    private static final long TOAST_MS = 2500L;

    /** 临时提示的颜色 */
    private static final int COLOR_TOAST = 0xFFFFDD44;

    /**
     * 打印键的颜色。
     *
     * 与编辑页那个「保存」是同一个绿：手机里这两个都是"点了会有结果"的动作键，
     * 用同一种颜色，玩家扫一眼就知道哪些字是能点的。预览文字是灰的，绿色跳出来
     * 正好把可点的那部分标出来。
     */
    private static final int COLOR_PRINT = 0xFF66FF88;

    /** 待消费的"打开某条"请求，null 表示没有 */
    private Integer pendingOpen;

    /** 待消费的"新建一条"请求 */
    private boolean pendingNew;

    // ============================================================
    //  生命周期
    // ============================================================

    public void open() {
        scrollOffset = 0;
        hoveredIdx = -1;
        printHoveredIdx = -1;
        toast = "";
        pendingOpen = null;
        pendingNew = false;
        PacketDistributor.sendToServer(new RequestNoteListPacket());
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

    // ============================================================
    //  渲染
    // ============================================================

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

        // 提示压在最底下一行：那儿要么是空白，要么是被行高截掉的半行，
        // 盖住也不损失信息
        renderToast(g, font, x, bottom - font.lineHeight, w);
    }

    /** 标题行：左侧标题，右侧"＋"按钮 */
    private int renderHeader(GuiGraphics g, Font font, int x, int y, int w,
                             int mouseX, int mouseY) {
        g.drawString(font, Component.translatable("mcphone.app.notes").getString(),
                x, y, PhoneTheme.FONT_COLOR_TITLE, true);

        String plus = "+";
        int plusW = font.width(plus);
        int plusX = x + w - plusW - 2;
        addHovered = mouseX >= plusX - 3 && mouseX <= plusX + plusW + 3
                  && mouseY >= y - 2 && mouseY <= y + font.lineHeight + 2;
        g.drawString(font, plus, plusX, y, addHovered ? 0xFFFFFFFF : 0xFF88CCFF, true);

        y += font.lineHeight + 4;
        g.fill(x, y, x + w, y + 1, 0x44FFFFFF);
        return y + 4;
    }

    private void renderEmpty(GuiGraphics g, Font font, int x, int y, int w) {
        g.drawString(font, Component.translatable("mcphone.notes.empty").getString(),
                x, y, 0xFF888888, false);
        y += font.lineHeight + 2;
        for (var line : font.split(Component.translatable("mcphone.notes.empty_hint"), w)) {
            g.drawString(font, line, x, y, 0xFF666666, false);
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

            // 右侧时间先算宽度，标题才知道能占多少
            String time = formatTime(note.modified());
            int timeW = font.width(time);
            g.drawString(font, time, x + w - timeW, y, COLOR_TIME, false);

            // 正文第一行为空的笔记显示"无标题"，否则列表里会出现一行空白
            String title = note.title().isEmpty()
                    ? Component.translatable("mcphone.notes.untitled").getString()
                    : note.title();
            g.drawString(font, truncate(font, title, w - timeW - 4), x, y, COLOR_TITLE, false);

            // 预览行右端是「打印」。放这一行而不是标题行：标题行右边已经被
            // 时间占了，而预览天生是可以截断的那一个
            int printW = font.width(print);
            int printX = x + w - printW;
            boolean printHover = mouseX >= printX - 2 && mouseX <= x + w
                    && mouseY >= y + font.lineHeight && mouseY < y + rowH;
            if (printHover) printHoveredIdx = i;

            g.drawString(font, truncate(font, note.preview(), w - printW - 4),
                    x, y + font.lineHeight + 1, COLOR_PREVIEW, false);

            // 每一行都画，不是只在悬停那行画：只给悬停行画的话，鼠标一进来
            // 预览就突然被截短，整行文字跳一下，比多几个字更闹
            g.drawString(font, print, printX, y + font.lineHeight + 1,
                    printHover ? COLOR_TITLE : COLOR_PRINT, false);

            y += rowH;
        }
    }

    // ============================================================
    //  鼠标
    // ============================================================

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        if (addHovered) {
            pendingNew = true;
            return true;
        }

        List<NoteSummary> list = NotesClientCache.getSummaries();

        // 打印要抢在整行之前判：两块区域是重叠的，反过来的话点打印会变成
        // 打开笔记，而玩家根本不会想到是自己点歪了
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

    /**
     * 把某条笔记印成一本书。
     *
     * 只发 id，正文以服务端存的那份为准。留在列表里不做任何跳转：打印不改变
     * 笔记本身，把人踢去别的界面反而像是出了什么事。
     */
    private void print(int id) {
        // 背包在客户端是齐全的，够不够这里就能算准——不必先发一趟包等服务端
        // 拒绝了再回话。缺书是最常见的失败，就地说清楚
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !NotePrinter.COST.canAfford(mc.player)) {
            showToast("mcphone.notes.print_failed");
            return;
        }

        PacketDistributor.sendToServer(new PrintNotePacket(id));
        showToast("mcphone.notes.print_done");
    }

    private void showToast(String translationKey) {
        toast = Component.translatable(translationKey).getString();
        toastUntilMs = System.currentTimeMillis() + TOAST_MS;
    }

    /** 提示到点自动消失 */
    private void renderToast(GuiGraphics g, Font font, int x, int y, int w) {
        if (toast.isEmpty() || System.currentTimeMillis() > toastUntilMs) return;
        int tw = font.width(toast);
        g.drawString(font, toast, x + (w - tw) / 2, y, COLOR_TOAST, false);
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

    // ============================================================
    //  内部
    // ============================================================

    /** 每行两行文字：标题 + 预览 */
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

    /** 今天的显示时刻，更早的显示日期——与会话列表同一个取舍 */
    private static String formatTime(long time) {
        if (time <= 0) return "";
        var zone = ZoneId.systemDefault();
        var dateTime = Instant.ofEpochMilli(time).atZone(zone);
        return dateTime.toLocalDate().equals(LocalDate.now(zone))
                ? dateTime.format(TIME_FORMAT)
                : dateTime.format(DATE_FORMAT);
    }

    private static String truncate(Font font, String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
    }
}
