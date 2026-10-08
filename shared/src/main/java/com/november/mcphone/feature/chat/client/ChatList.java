package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.ServerConfig;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.client.PlayerAvatar;
import com.november.mcphone.core.client.UnreadBadge;
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

    /** 三个聊天页面共用头像尺寸，给姓名与消息留出更多空间。 */
    private static final int AVATAR_SIZE = ChatLayout.AVATAR_SIZE;

    /** 改这个数要连贴图一起改：不做平滑缩放，尺寸对不上会抽像素 */
    private static final int TP_ICON_SIZE = ChatLayout.TELEPORT_ICON_SIZE;
    private static final String[] TAB_KEYS = {"chats", "contacts", "discover", "profile"};
    private static final ChatUi.Icon[] TAB_ICONS = {
            ChatUi.Icon.CHAT, ChatUi.Icon.CONTACTS, ChatUi.Icon.DISCOVER, ChatUi.Icon.PROFILE};

    private static int colorName() { return FontPalette.title(); }
    private static int colorPreview() { return FontPalette.preview(); }
    private static int colorTime() { return FontPalette.timestamp(); }

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
        final int tabsY = phoneTop + screenH - navH - ChatLayout.TAB_HEIGHT;
        final int bottom = tabsY - ChatLayout.SECTION_GAP;
        int y = phoneTop + statusH + ChatLayout.TOP_GAP;

        y = renderHeader(g, font, x, y, w, mouseX, mouseY);
        renderTabs(g, font, x, tabsY, w, mouseX, mouseY);

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
        int headerTextY = y + (ChatLayout.HEADER_HEIGHT - ChatUi.lineHeight(font, ChatLayout.TEXT_SCALE)) / 2;
        ChatUi.centeredText(g, font, title, x + w / 2f,
                headerTextY, ChatLayout.TEXT_SCALE, FontPalette.title());
        int plusX = x + w - 10;
        addContactHovered = ChatLayout.hit(mouseX, mouseY, plusX - 2, y,
                12, ChatLayout.HEADER_HEIGHT);
        // 恢复原来的透明图标入口，不给加号额外套框。
        ChatUi.icon(g, ChatUi.Icon.PLUS, plusX,
                y + (ChatLayout.HEADER_HEIGHT - ChatLayout.ICON_SIZE) / 2,
                addContactHovered ? FontPalette.title() : FontPalette.link());
        y += ChatLayout.HEADER_HEIGHT + ChatLayout.SECTION_GAP;
        // 搜索仅预留外观位置，不接受输入，不过滤会话。
        ChatUi.glass(g, ChatGlass.Surface.SEARCH, x, y, w, ChatLayout.SEARCH_HEIGHT, false);
        String search = ChatUi.truncate(font, Component.translatable("mcphone.chat.layout.search").getString(),
                w - ChatLayout.SEARCH_TEXT_X - ChatLayout.SEARCH_PAD_X, ChatLayout.META_SCALE);
        ChatUi.sizedIcon(g, ChatUi.Icon.SEARCH, x + ChatLayout.SEARCH_PAD_X,
                y + (ChatLayout.SEARCH_HEIGHT - ChatLayout.SEARCH_ICON_SIZE) / 2,
                ChatLayout.SEARCH_ICON_SIZE, FontPalette.dim());
        ChatUi.text(g, font, search, x + ChatLayout.SEARCH_TEXT_X,
                y + (ChatLayout.SEARCH_HEIGHT - ChatUi.lineHeight(font, ChatLayout.META_SCALE)) / 2,
                ChatLayout.META_SCALE, FontPalette.dim());
        return y + ChatLayout.SEARCH_HEIGHT + ChatLayout.SECTION_GAP;
    }

    private void renderTabs(GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
        ChatUi.glass(g, ChatGlass.Surface.NAVIGATION, x, y, w, ChatLayout.TAB_HEIGHT, false);
        contactsTabHovered = ChatLayout.hit(mx, my, x + w / 4, y,
                w * 2 / 4 - w / 4, ChatLayout.TAB_HEIGHT);
        for (int i = 0; i < TAB_KEYS.length; i++) {
            int left = x + w * i / 4;
            int right = x + w * (i + 1) / 4;
            float centerX = (left + right) / 2f;
            int color = i == 0 ? ChatUi.accent()
                    : (i == 1 ? FontPalette.body() : FontPalette.dim());
            ChatUi.centeredIcon(g, TAB_ICONS[i], centerX,
                    y + ChatLayout.TAB_ICON_TOP, color);
            String label = ChatUi.truncate(font,
                    Component.translatable("mcphone.chat.layout." + TAB_KEYS[i]).getString(),
                    right - left - 2, ChatLayout.TAB_LABEL_SCALE);
            ChatUi.centeredText(g, font, label, centerX,
                    y + ChatLayout.TAB_LABEL_TOP, ChatLayout.TAB_LABEL_SCALE, color);
            if (i == 0) {
                int underlineW = Math.min(16, right - left - 4);
                int underlineX = Math.round(centerX - underlineW / 2f);
                g.fill(underlineX, y + ChatLayout.TAB_HEIGHT - 1,
                        underlineX + underlineW, y + ChatLayout.TAB_HEIGHT, color);
            }
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
            boolean hovered = ChatLayout.hit(mouseX, mouseY, x, y, w, rowH);
            if (hovered) hoveredPeer = c.id();
            ChatUi.glass(g, ChatGlass.Surface.CARD, x, y, w, rowH, hovered);

            if (renderRow(g, font, c, x, y, w, mouseX, mouseY)) teleportHoveredPeer = c.id();
            y += rowH + ChatLayout.CARD_GAP;
        }
    }

    /** 返回鼠标是否停在这一行的传送图标上 */
    private boolean renderRow(GuiGraphics g, Font font, ConversationSummary c,
                              int x, int y, int w, int mouseX, int mouseY) {
        // 时间在右上、未读在右下；头像不再被数字压住。
        String right = GuiUtil.formatTime(c.lastTime());
        int rightW = ChatUi.width(font, right, ChatLayout.META_SCALE);
        // 传送图标只给在线的人。这里读的是【服务端】的开关 —— 它读得到，靠的是加载器
        // 把 SERVER 档的配置推给客户端；哪些目标做得到，见 versions/targets.json 的
        // server_config_sync。读错了只是图标显隐不对，真正的拦截在 TeleportService。
        boolean canTeleport = c.online() && ServerConfig.allowFriendTeleport();
        String unread = c.unread() > 0 ? unreadLabel(c.unread()) : "";
        int badgeW = unread.isEmpty() ? 0 : Math.max(ChatLayout.UNREAD_HEIGHT, UnreadBadge.width(font, unread));
        var layout = ChatLayout.card(w, ChatUi.lineHeight(font, ChatLayout.TEXT_SCALE),
                ChatUi.lineHeight(font, ChatLayout.META_SCALE), rightW, badgeW, canTeleport);
        var frame = layout.avatarFrame();
        ChatUi.glass(g, ChatGlass.Surface.AVATAR, x + frame.x(), y + frame.y(),
                frame.width(), frame.height(), false);
        PlayerAvatar.draw(g, c.id(), x + frame.x() + ChatLayout.AVATAR_FRAME,
                y + frame.y() + ChatLayout.AVATAR_FRAME, AVATAR_SIZE);
        // 小状态方点落在外框边角，不再用一大块黑色描边遮住脸部。
        int dot = ChatLayout.AVATAR_STATUS_SIZE;
        int dotX = x + frame.x() + frame.width() - dot;
        int dotY = y + frame.y() + frame.height() - dot;
        g.fill(dotX, dotY, dotX + dot, dotY + dot,
                c.online() ? PlayerAvatar.COLOR_ONLINE : PlayerAvatar.COLOR_OFFLINE);

        int nameX = x + layout.textX();
        String name = ChatUi.truncate(font, c.name(), layout.nameWidth(), ChatLayout.TEXT_SCALE);
        ChatUi.text(g, font, name, nameX, y + layout.nameY(), ChatLayout.TEXT_SCALE, colorName());
        boolean tpHovered = canTeleport && layout.teleportHit().contains(mouseX - x, mouseY - y);

        if (!right.isEmpty()) {
            ChatUi.text(g, font, right, x + layout.timeX(), y + layout.timeY(), ChatLayout.META_SCALE, colorTime());
        }
        if (c.unread() > 0) {
            var badge = layout.unread();
            ChatUi.glass(g, ChatGlass.Surface.BADGE, x + badge.x(), y + badge.y(),
                    badge.width(), badge.height(), false);
            UnreadBadge.label(g, font, unread, x + badge.x() + badge.width() / 2f,
                    y + badge.y() + badge.height() / 2f, ChatLayout.META_SCALE, 0xFFFFF7FB);
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
        ChatUi.preview(g, font, preview, nameX, y + layout.previewY(), layout.previewWidth(), previewColor);

        if (canTeleport) renderTeleportIcon(g, font, x, y, layout, tpHovered);

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
                                           int x, int y, ChatLayout.Card layout, boolean hovered) {
        var hit = layout.teleportHit();
        // 操作底板和数字角标同高、同中心，不另画白色悬停块。
        ChatUi.glass(g, ChatGlass.Surface.BUTTON, x + hit.x(), y + hit.y(), hit.width(), hit.height(), hovered);
        g.pose().pushPose();
        try {
            g.pose().translate(x + layout.teleportX(), y + layout.teleportY(), 0);
            if (!PhoneSkin.draw(g, PhoneSkin.Element.CHAT_TELEPORT, 0, 0, TP_ICON_SIZE, TP_ICON_SIZE)) {
                ChatUi.centeredText(g, font, "→", TP_ICON_SIZE / 2f, 0, ChatLayout.TEXT_SCALE,
                        hovered ? FontPalette.title() : FontPalette.link());
            }
        } finally {
            g.pose().popPose();
        }
    }

    private void maybeRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < REFRESH_INTERVAL_MS) return;

        lastRequestMs = now;
        MCphoneNetwork.sendToServer(new RequestConversationsPacket());
    }

    private static int rowHeight(Font font) {
        return ChatLayout.cardHeight(ChatUi.lineHeight(font, ChatLayout.TEXT_SCALE),
                ChatUi.lineHeight(font, ChatLayout.META_SCALE));
    }

    private void clampScroll(int total, int availableHeight, Font font) {
        int visible = ChatLayout.visibleCards(availableHeight, rowHeight(font));
        int maxOffset = Math.max(0, total - visible);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
        if (scrollOffset < 0) scrollOffset = 0;
    }

    private static String unreadLabel(int unread) {
        return unread > 99 ? "99+" : String.valueOf(unread);
    }

}
