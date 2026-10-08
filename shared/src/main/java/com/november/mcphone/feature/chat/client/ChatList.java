package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.ServerConfig;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.client.PlayerAvatar;
import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.feature.chat.net.ChatClientCache;
import com.november.mcphone.feature.chat.net.ConversationSummary;
import com.november.mcphone.feature.chat.net.RequestConversationsPacket;
import com.november.mcphone.feature.chat.net.TeleportToFriendPacket;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * 聊天 App 的会话列表。数据全来自 ChatClientCache；在线状态与未读数没有推送，
 * 列表可见期间每几秒拉一次摘要。
 * 悬停记的是 UUID 而不是行号：列表每 3 秒被整份换掉，按下标重查会点到别人，
 * 而这一列有传送这种撤不回的动作。
 */
public final class ChatList {

    private static final int PAD = 4;

    private static final long REFRESH_INTERVAL_MS = 3000L;

    /** 须是皮肤头部 8×8 的整数倍，否则放大后边缘毛糙 */
    private static final int AVATAR_SIZE = 16;

    private static final int AVATAR_GAP = 3;

    /** 改这个数要连贴图一起改：不做平滑缩放，尺寸对不上会抽像素 */
    private static final int TP_ICON_SIZE = 7;

    private static final int TP_GAP = 3;

    private static final int TP_HIT_PAD = 3;
    private static final String[] TAB_KEYS = {"chats", "contacts", "discover", "profile"};
    private static final ChatUi.Icon[] TAB_ICONS = {
            ChatUi.Icon.CHAT, ChatUi.Icon.CONTACTS, ChatUi.Icon.DISCOVER, ChatUi.Icon.PROFILE};

    private static int colorName() { return FontPalette.title(); }
    private static int colorPreview() { return FontPalette.preview(); }
    private static int colorTime() { return FontPalette.timestamp(); }
    private static final int COLOR_UNREAD_BG = PhoneTheme.COLOR_UNREAD_BADGE;
    private static final int COLOR_ROW_HOVER = PhoneTheme.COLOR_ROW_HOVER;

    private long lastRequestMs;
    private int scrollOffset;
    private boolean addContactHovered;
    private boolean contactsTabHovered;

    /** 鼠标停在谁那一行上，null 表示没有。记人不记下标，理由见类注释 */
    private UUID hoveredPeer;

    private UUID pendingOpen;

    private boolean pendingAddContact;

    private UUID teleportHoveredPeer;

    private boolean pendingClose;

    public void open() {
        scrollOffset = 0;
        hoveredPeer = null;
        lastRequestMs = 0L;      // 置零＝下一帧立即请求
        pendingOpen = null;
        pendingAddContact = false;
        teleportHoveredPeer = null;
        pendingClose = false;
    }

    public void close() {
        hoveredPeer = null;
        addContactHovered = false;
        contactsTabHovered = false;
        teleportHoveredPeer = null;
    }

    /** 取走"打开某个会话"的请求，没有则返回 null */
    public UUID consumeOpenRequest() {
        UUID out = pendingOpen;
        pendingOpen = null;
        return out;
    }

    public boolean consumeAddContactRequest() {
        boolean out = pendingAddContact;
        pendingAddContact = false;
        return out;
    }

    /** 点了传送就该关机；关机只有 PhoneScreen 做得了，本类只提请求 */
    public boolean consumeCloseRequest() {
        boolean out = pendingClose;
        pendingClose = false;
        return out;
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        maybeRefresh();

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;
        final int bottom = phoneTop + screenH - navH - ChatLayout.TAB_HEIGHT;
        int y = phoneTop + statusH + 4;

        y = renderHeader(g, font, x, y, w, mouseX, mouseY);
        renderTabs(g, font, x, bottom, w, mouseX, mouseY);

        List<ConversationSummary> list = ChatClientCache.getConversations();
        if (list.isEmpty()) {
            renderEmpty(g, font, x, y, w);
            hoveredPeer = null;
            teleportHoveredPeer = null;
            return;
        }

        clampScroll(list.size(), bottom - y, font);
        renderRows(g, font, list, x, y, w, bottom, mouseX, mouseY);
    }

    private int renderHeader(GuiGraphics g, Font font, int x, int y, int w,
                             int mouseX, int mouseY) {
        String title = Component.translatable("mcphone.app.chat").getString();
        title = ChatUi.truncate(font, title, w - 28, ChatLayout.TEXT_SCALE);
        ChatUi.text(g, font, title, x + (w - ChatUi.width(font, title, ChatLayout.TEXT_SCALE)) / 2,
                y + 2, ChatLayout.TEXT_SCALE, FontPalette.title());
        int plusX = x + w - 10;
        addContactHovered = GuiUtil.hit(mouseX, mouseY, plusX - 2, y, 13, 13);
        ChatUi.icon(g, ChatUi.Icon.PLUS, plusX, y + 1,
                addContactHovered ? FontPalette.title() : FontPalette.link());
        y += 16;
        // 搜索仅预留外观位置，不接受输入，不过滤会话。
        g.fill(x, y, x + w, y + 14, PhoneTheme.COLOR_CHAT_INPUT_BG);
        String search = Component.translatable("mcphone.chat.layout.search").getString();
        int searchW = ChatUi.width(font, search, ChatLayout.TEXT_SCALE);
        int searchX = x + (w - searchW - 13) / 2;
        ChatUi.icon(g, ChatUi.Icon.SEARCH, searchX, y + 2, FontPalette.dim());
        ChatUi.text(g, font, search, searchX + 13, y + 3, ChatLayout.TEXT_SCALE, FontPalette.dim());
        y += 18;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        return y + 4;
    }

    private void renderTabs(GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
        g.fill(x, y, x + w, y + ChatLayout.TAB_HEIGHT, PhoneTheme.COLOR_CHAT_INPUT_BG);
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        contactsTabHovered = GuiUtil.hit(mx, my, x + w / 4, y, w / 4, ChatLayout.TAB_HEIGHT);
        for (int i = 0; i < TAB_KEYS.length; i++) {
            int left = x + w * i / 4;
            int right = x + w * (i + 1) / 4;
            int color = i == 0 ? PhoneTheme.FONT_COLOR_CHAT_SEND
                    : (i == 1 ? FontPalette.body() : FontPalette.dim());
            ChatUi.icon(g, TAB_ICONS[i], (left + right - 9) / 2, y + 3, color);
            String label = ChatUi.truncate(font,
                    Component.translatable("mcphone.chat.layout." + TAB_KEYS[i]).getString(),
                    right - left - 2, ChatLayout.META_SCALE);
            ChatUi.text(g, font, label, (left + right - ChatUi.width(font, label, ChatLayout.META_SCALE)) / 2,
                    y + 15, ChatLayout.META_SCALE, color);
        }
    }

    private void renderEmpty(GuiGraphics g, Font font, int x, int y, int w) {
        ChatUi.text(g, font, Component.translatable("mcphone.chat.empty").getString(),
                x, y, ChatLayout.TEXT_SCALE, FontPalette.subtle());
        y += ChatUi.lineHeight(font, ChatLayout.TEXT_SCALE) + 2;

        for (var line : font.split(Component.translatable("mcphone.chat.empty_hint"),
                ChatLayout.unscaledWidth(w, ChatLayout.TEXT_SCALE))) {
            ChatUi.text(g, font, line, x, y, ChatLayout.TEXT_SCALE, FontPalette.dim());
            y += ChatUi.lineHeight(font, ChatLayout.TEXT_SCALE);
        }
    }

    private void renderRows(GuiGraphics g, Font font, List<ConversationSummary> list,
                            int x, int y, int w, int bottom, int mouseX, int mouseY) {
        final int rowH = rowHeight(font);
        hoveredPeer = null;
        teleportHoveredPeer = null;

        for (int i = scrollOffset; i < list.size(); i++) {
            if (y + rowH > bottom) break;

            ConversationSummary c = list.get(i);
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + rowH;
            if (hovered) {
                hoveredPeer = c.id();
                g.fill(x, y, x + w, y + rowH, COLOR_ROW_HOVER);
            }

            if (renderRow(g, font, c, x, y, w, mouseX, mouseY)) teleportHoveredPeer = c.id();
            y += rowH;
        }
    }

    /** 返回鼠标是否停在这一行的传送图标上 */
    private boolean renderRow(GuiGraphics g, Font font, ConversationSummary c,
                              int x, int y, int w, int mouseX, int mouseY) {
        int avatarY = y + (rowHeight(font) - AVATAR_SIZE) / 2;
        PlayerAvatar.drawWithStatus(g, c.id(), x, avatarY, AVATAR_SIZE, c.online());

        // 时间始终在右侧，未读移到头像右上角，避免把时间挤掉。
        String right = GuiUtil.formatTime(c.lastTime());
        int rightW = ChatUi.width(font, right, ChatLayout.META_SCALE);

        int nameX = x + AVATAR_SIZE + AVATAR_GAP;
        int nameMaxW = w - (nameX - x) - rightW - 4;
        String name = ChatUi.truncate(font, c.name(), nameMaxW, ChatLayout.TEXT_SCALE);
        ChatUi.text(g, font, name, nameX, y + 3, ChatLayout.TEXT_SCALE, colorName());

        // 传送图标只给在线的人。这里读的是【服务端】的开关 —— 它读得到，靠的是加载器
        // 把 SERVER 档的配置推给客户端；哪些目标做得到，见 versions/targets.json 的
        // server_config_sync。读错了只是图标显隐不对，真正的拦截在 TeleportService。
        // 位置先算、图最后画：第二行预览要按它让出的宽度截断
        boolean canTeleport = c.online() && ServerConfig.allowFriendTeleport();
        int secondLineY = y + 12;
        int tpW = 0;
        int tpX = 0;
        int tpY = 0;
        boolean tpHovered = false;
        if (canTeleport) {
            // 往里缩 TP_HIT_PAD：悬停高亮铺的是点击区，贴边会凸出整行高亮
            tpW = TP_ICON_SIZE + TP_HIT_PAD + TP_GAP;
            tpX = x + w - TP_ICON_SIZE - TP_HIT_PAD;
            tpY = secondLineY;
            tpHovered = GuiUtil.hit(mouseX, mouseY,
                    tpX - TP_HIT_PAD, tpY - TP_HIT_PAD,
                    TP_ICON_SIZE + TP_HIT_PAD * 2, TP_ICON_SIZE + TP_HIT_PAD * 2);
        }

        if (!right.isEmpty()) {
            int rx = x + w - rightW;
            ChatUi.text(g, font, right, rx, y + 3, ChatLayout.META_SCALE, colorTime());
        }
        if (c.unread() > 0) {
            String unread = unreadLabel(c.unread());
            int badgeW = ChatUi.width(font, unread, ChatLayout.META_SCALE) + 4;
            int badgeX = x + AVATAR_SIZE - badgeW + 2;
            PhoneSkin.drawOrFill(g, PhoneSkin.Element.UNREAD_BADGE,
                    badgeX, avatarY - 2, badgeW, 8, COLOR_UNREAD_BG);
            ChatUi.text(g, font, unread, badgeX + 2, avatarY - 1,
                    ChatLayout.META_SCALE, PhoneTheme.FONT_COLOR_BADGE);
        }

        // 第二行：消息预览，停在传送图标上时改说传送提示
        String preview;
        int previewColor;
        if (tpHovered) {
            preview = Component.translatable("mcphone.chat.teleport_hint").getString();
            previewColor = FontPalette.link();
        } else {
            // 显示成什么由那条消息自己说（文本是正文，图片是「[图片]」），见 MessageBody.preview
            preview = c.last().isPresent()
                    ? c.last().get().preview().getString()
                    : Component.translatable("mcphone.chat.no_message").getString();
            previewColor = colorPreview();
        }
        ChatUi.text(g, font, ChatUi.truncate(font, preview, w - (nameX - x) - tpW, ChatLayout.META_SCALE),
                nameX, secondLineY, ChatLayout.META_SCALE, previewColor);

        if (canTeleport) renderTeleportIcon(g, font, tpX, tpY, tpHovered);
        g.fill(nameX, y + rowHeight(font) - 1, x + w, y + rowHeight(font), PhoneTheme.COLOR_DIVIDER_FAINT);

        return tpHovered;
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        if (addContactHovered || contactsTabHovered) {
            pendingAddContact = true;
            return true;
        }

        // 必须先判传送：图标的点击区整个落在那一行里面，后判会连带打开会话
        if (teleportHoveredPeer != null) {
            MCphoneNetwork.sendToServer(new TeleportToFriendPacket(teleportHoveredPeer));
            pendingClose = true;
            return true;
        }

        if (hoveredPeer != null) {
            pendingOpen = hoveredPeer;
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

    /** 传送图标：悬停先铺高亮，再画贴图，没有贴图就画 → 字符兜底 */
    private static void renderTeleportIcon(GuiGraphics g, Font font,
                                           int x, int y, boolean hovered) {
        if (hovered) {
            g.fill(x - TP_HIT_PAD, y - TP_HIT_PAD,
                    x + TP_ICON_SIZE + TP_HIT_PAD, y + TP_ICON_SIZE + TP_HIT_PAD,
                    PhoneTheme.COLOR_HOVER_STRONG);
        }

        if (PhoneSkin.draw(g, PhoneSkin.Element.CHAT_TELEPORT,
                x, y, TP_ICON_SIZE, TP_ICON_SIZE)) {
            return;
        }

        String glyph = "→";
        g.drawString(font, glyph, x + (TP_ICON_SIZE - font.width(glyph)) / 2, y,
                hovered ? FontPalette.title() : FontPalette.link(), false);
    }

    private void maybeRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < REFRESH_INTERVAL_MS) return;

        lastRequestMs = now;
        MCphoneNetwork.sendToServer(new RequestConversationsPacket());
    }

    private static int rowHeight(Font font) {
        return Math.max(AVATAR_SIZE + 6, ChatUi.lineHeight(font, ChatLayout.TEXT_SCALE)
                + ChatUi.lineHeight(font, ChatLayout.META_SCALE) + 9);
    }

    private void clampScroll(int total, int availableHeight, Font font) {
        int visible = Math.max(1, availableHeight / rowHeight(font));
        int maxOffset = Math.max(0, total - visible);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
        if (scrollOffset < 0) scrollOffset = 0;
    }

    private static String unreadLabel(int unread) {
        return unread > 99 ? "99+" : String.valueOf(unread);
    }

}
