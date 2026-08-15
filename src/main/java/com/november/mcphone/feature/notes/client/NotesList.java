package com.november.mcphone.feature.notes.client;

import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.feature.notes.NoteSummary;
import com.november.mcphone.feature.notes.net.NotesClientCache;
import com.november.mcphone.feature.notes.net.RequestNoteListPacket;
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
        pendingOpen = null;
        pendingNew = false;
        PacketDistributor.sendToServer(new RequestNoteListPacket());
    }

    public void close() {
        hoveredIdx = -1;
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
            return;
        }

        clampScroll(list.size(), bottom - y, font);
        renderRows(g, font, list, x, y, w, bottom, mouseX, mouseY);
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
        hoveredIdx = -1;

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

            g.drawString(font, truncate(font, note.preview(), w),
                    x, y + font.lineHeight + 1, COLOR_PREVIEW, false);

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
        if (hoveredIdx >= 0 && hoveredIdx < list.size()) {
            pendingOpen = list.get(hoveredIdx).id();
            return true;
        }
        return false;
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
