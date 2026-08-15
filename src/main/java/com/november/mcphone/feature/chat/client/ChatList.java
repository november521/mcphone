package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.client.PlayerAvatar;
import com.november.mcphone.feature.chat.net.ChatClientCache;
import com.november.mcphone.feature.chat.net.ConversationSummary;
import com.november.mcphone.feature.chat.net.RequestConversationsPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 聊天 App 的会话列表。
 *
 * 数据全部来自 {@link ChatClientCache}，本类不持有真值——服务端才是权威，
 * 这里只负责把那份快照画出来。
 *
 * ============================================================
 * 为什么要定时刷新
 * ============================================================
 *
 * 新消息是服务端主动推的，不需要轮询。但【在线状态】和【未读数】不是：
 * 联系人上线下线不会通知我们，未读数也只有服务端算得出。所以列表打开
 * 期间每隔几秒拉一次摘要。
 *
 * 只在列表可见时才拉——关掉 App 就停，不会在后台空转。
 */
public final class ChatList {

    /** 左右内边距 */
    private static final int PAD = 4;

    /** 刷新间隔。够短，上线下线看得出来；够长，不至于每帧发包 */
    private static final long REFRESH_INTERVAL_MS = 3000L;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM-dd");

    /** 头像边长。16 ＝ 皮肤头部 8×8 的整数倍放大，边缘才锐利 */
    private static final int AVATAR_SIZE = 16;

    /** 头像与右侧文字之间的空隙 */
    private static final int AVATAR_GAP = 3;

    // ---- 颜色 ----
    private static final int COLOR_NAME = 0xFFFFFFFF;
    private static final int COLOR_PREVIEW = 0xFF999999;
    private static final int COLOR_TIME = 0xFF777777;
    private static final int COLOR_UNREAD_BG = 0xFFDD3333;
    private static final int COLOR_ROW_HOVER = 0x33FFFFFF;

    private long lastRequestMs;
    private int scrollOffset;
    private int hoveredIdx = -1;
    private boolean addContactHovered;

    /** 待消费的"打开某个会话"请求 */
    private UUID pendingOpen;

    /** 待消费的"打开加联系人界面"请求 */
    private boolean pendingAddContact;

    // ============================================================
    //  生命周期
    // ============================================================

    /** 进入会话列表：立刻拉一次，不必等定时刷新 */
    public void open() {
        scrollOffset = 0;
        hoveredIdx = -1;
        lastRequestMs = 0L;      // 置零＝下一帧立即请求
        pendingOpen = null;
        pendingAddContact = false;
    }

    /** 离开会话列表：停止定时刷新 */
    public void close() {
        hoveredIdx = -1;
        addContactHovered = false;
    }

    /** 取走"打开某个会话"的请求，没有则返回 null */
    public UUID consumeOpenRequest() {
        UUID out = pendingOpen;
        pendingOpen = null;
        return out;
    }

    /** 取走"打开加联系人界面"的请求 */
    public boolean consumeAddContactRequest() {
        boolean out = pendingAddContact;
        pendingAddContact = false;
        return out;
    }

    // ============================================================
    //  渲染
    // ============================================================

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        maybeRefresh();

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;
        final int bottom = phoneTop + screenH - navH;
        int y = phoneTop + statusH + 4;

        y = renderHeader(g, font, x, y, w, mouseX, mouseY);

        List<ConversationSummary> list = ChatClientCache.getConversations();
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
        g.drawString(font, Component.translatable("mcphone.app.chat").getString(),
                x, y, PhoneTheme.FONT_COLOR_TITLE, true);

        String plus = "+";
        int plusW = font.width(plus);
        int plusX = x + w - plusW - 2;
        addContactHovered = mouseX >= plusX - 3 && mouseX <= plusX + plusW + 3
                         && mouseY >= y - 2 && mouseY <= y + font.lineHeight + 2;
        g.drawString(font, plus, plusX, y,
                addContactHovered ? 0xFFFFFFFF : 0xFF88CCFF, true);

        y += font.lineHeight + 4;
        g.fill(x, y, x + w, y + 1, 0x44FFFFFF);
        return y + 4;
    }

    private void renderEmpty(GuiGraphics g, Font font, int x, int y, int w) {
        g.drawString(font, Component.translatable("mcphone.chat.empty").getString(),
                x, y, 0xFF888888, false);
        y += font.lineHeight + 2;

        // 提示文字比屏幕宽时按词换行，硬截断会把话说一半
        for (var line : font.split(Component.translatable("mcphone.chat.empty_hint"), w)) {
            g.drawString(font, line, x, y, 0xFF666666, false);
            y += font.lineHeight;
        }
    }

    private void renderRows(GuiGraphics g, Font font, List<ConversationSummary> list,
                            int x, int y, int w, int bottom, int mouseX, int mouseY) {
        final int rowH = rowHeight(font);
        hoveredIdx = -1;

        for (int i = scrollOffset; i < list.size(); i++) {
            if (y + rowH > bottom) break;

            ConversationSummary c = list.get(i);
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + rowH;
            if (hovered) {
                hoveredIdx = i;
                g.fill(x, y, x + w, y + rowH, COLOR_ROW_HOVER);
            }

            renderRow(g, font, c, x, y, w);
            y += rowH;
        }
    }

    private void renderRow(GuiGraphics g, Font font, ConversationSummary c,
                           int x, int y, int w) {
        // ---- 头像：竖跨两行文字，在行内居中 ----
        // 在线状态点挂在头像右下角，比单独占一列省地方，也更像真手机
        int avatarY = y + (rowHeight(font) - AVATAR_SIZE) / 2;
        PlayerAvatar.drawWithStatus(g, c.id(), x, avatarY, AVATAR_SIZE, c.online());

        // 右侧先算宽度，名字才知道能占多少
        String right = c.unread() > 0 ? unreadLabel(c.unread()) : formatTime(c.lastTime());
        int rightW = right.isEmpty() ? 0 : font.width(right) + (c.unread() > 0 ? 4 : 0);

        int nameX = x + AVATAR_SIZE + AVATAR_GAP;
        int nameMaxW = w - (nameX - x) - rightW - 4;
        String name = truncate(font, c.name(), nameMaxW);
        g.drawString(font, name, nameX, y, COLOR_NAME, false);

        if (!right.isEmpty()) {
            int rx = x + w - rightW;
            if (c.unread() > 0) {
                // 未读用红底白字，一眼能看见；纯红字在深色壁纸上容易糊掉。
                // 底走换肤，与消息通知里的角标共用同一张贴图
                PhoneSkin.drawOrFill(g, PhoneSkin.Element.UNREAD_BADGE,
                        rx - 1, y - 1, x + w - (rx - 1), font.lineHeight + 1, COLOR_UNREAD_BG);
                g.drawString(font, right, rx + 1, y, 0xFFFFFFFF, false);
            } else {
                g.drawString(font, right, rx, y, COLOR_TIME, false);
            }
        }

        // ---- 第二行：最后一条消息预览 ----
        String preview = c.lastText().isEmpty()
                ? Component.translatable("mcphone.chat.no_message").getString()
                : c.lastText();
        g.drawString(font, truncate(font, preview, w - (nameX - x)),
                nameX, y + font.lineHeight + 1, COLOR_PREVIEW, false);
    }

    // ============================================================
    //  鼠标
    // ============================================================

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        if (addContactHovered) {
            pendingAddContact = true;
            return true;
        }

        List<ConversationSummary> list = ChatClientCache.getConversations();
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
        if (scrollY < 0 && scrollOffset < ChatClientCache.getConversations().size() - 1) {
            scrollOffset++;
            return true;
        }
        return false;
    }

    // ============================================================
    //  内部
    // ============================================================

    /** 到点就再拉一次会话摘要 */
    private void maybeRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < REFRESH_INTERVAL_MS) return;

        lastRequestMs = now;
        PacketDistributor.sendToServer(new RequestConversationsPacket());
    }

    /** 每行两行文字：名字 + 预览 */
    private static int rowHeight(Font font) {
        return font.lineHeight * 2 + 4;
    }

    /**
     * 限制滚动位置。
     *
     * 列表变短时（联系人被删、会话减少）必须夹紧，否则会滚到空白处，
     * 玩家以为列表是空的。
     */
    private void clampScroll(int total, int availableHeight, Font font) {
        int visible = Math.max(1, availableHeight / rowHeight(font));
        int maxOffset = Math.max(0, total - visible);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
        if (scrollOffset < 0) scrollOffset = 0;
    }

    /** 未读数超过 99 就显示 99+，否则一个三位数会把整行挤变形 */
    private static String unreadLabel(int unread) {
        return unread > 99 ? "99+" : String.valueOf(unread);
    }

    /** 今天的消息显示时刻，更早的显示日期——手机上就这么干的 */
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
