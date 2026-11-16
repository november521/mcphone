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
import com.november.mcphone.feature.chat.group.GroupSummary;
import com.november.mcphone.feature.chat.group.net.GroupClientCache;
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
    private final ChatNavigationBar navigation = new ChatNavigationBar();
    private final ChatContactSearch search = new ChatContactSearch();
    private String rowQuery = "";

    private static int colorName() { return FontPalette.title(); }
    private static int colorPreview() { return FontPalette.preview(); }
    private static int colorTime() { return FontPalette.timestamp(); }

    private long lastRequestMs;
    private int scrollOffset;
    private ChatLayout.Rect plusBounds;
    private final ChatHomeMenu homeMenu = new ChatHomeMenu();
    private boolean pendingCreate, hoveredGroup;
    private UUID pendingGroup;
    private record Row(ConversationSummary summary, GroupSummary group) {}
    private List<ConversationSummary> rowFriends;
    private List<GroupSummary> rowGroups;
    private List<Row> cachedRows = List.of();
    private boolean pendingContacts, pendingProfile;

    /** 鼠标停在谁那一行上，null 表示没有。记人不记下标，理由见类注释 */
    private UUID hoveredPeer;

    private UUID pendingOpen;

    private boolean pendingAddContact;

    private UUID teleportHoveredPeer;

    private boolean pendingClose;

    public void open() {
        close();
        scrollOffset = 0;
        lastRequestMs = 0L;      // 置零＝下一帧立即请求
    }

    public void close() {
        hoveredPeer = null;
        plusBounds = null;
        navigation.clear();
        pendingContacts = false; pendingProfile = false;
        teleportHoveredPeer = null;
        pendingOpen = null;
        pendingAddContact = false;
        pendingClose = false;
        homeMenu.dismiss();
        pendingCreate = hoveredGroup = false; pendingGroup = null;
        rowFriends = null; rowGroups = null; cachedRows = List.of();
        search.reset(); rowQuery = ""; scrollOffset = 0;
    }

    public boolean hasContextMenu() { return homeMenu.isOpen(); }
    public boolean dismissContextMenu() { return homeMenu.dismiss(); }

    /** 机身外与导航栏的点击也先交到这里，只处理菜单，不让操作穿透。 */
    public boolean contextMouseClicked(double x, double y, int button) {
        if (!hasContextMenu()) return false;
        var action = homeMenu.choose(x, y, button);
        if (action == ChatHomeMenu.Action.ADD_CONTACT) pendingAddContact = true;
        else if (action == ChatHomeMenu.Action.CREATE_GROUP) pendingCreate = true;
        return true;
    }

    /** 取走"打开某个会话"的请求，没有则返回 null */
    public UUID consumeOpenRequest() {
        UUID out = pendingOpen;
        pendingOpen = null;
        return out;
    }
    public UUID consumeGroupOpenRequest() { var out = pendingGroup; pendingGroup = null; return out; }
    public boolean consumeCreateRequest() { boolean out = pendingCreate; pendingCreate = false; return out; }

    public boolean consumeAddContactRequest() {
        boolean out = pendingAddContact;
        pendingAddContact = false;
        return out;
    }

    public boolean consumeProfileRequest() { boolean out=pendingProfile;pendingProfile=false;return out; }
    public boolean consumeContactsRequest() {
        boolean out = pendingContacts;
        pendingContacts = false;
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
        navigation.render(g, font, x, tabsY, w, ChatNavigationBar.Page.CHATS);

        List<Row> list = rows();
        if (list.isEmpty()) {
            renderEmpty(g, font, x, y, w);
            hoveredPeer = null;
            teleportHoveredPeer = null;
        } else {
            clampScroll(list.size(), bottom - y, font);
            renderRows(g, font, list, x, y, w, bottom, mouseX, mouseY);
        }
        int menuTop = phoneTop + statusH;
        homeMenu.render(g, font, x, menuTop, w, bottom - menuTop,
                plusBounds.x() + plusBounds.width(), plusBounds.y() + plusBounds.height() + 3, mouseX, mouseY);
    }

    private int renderHeader(GuiGraphics g, Font font, int x, int y, int w,
                             int mouseX, int mouseY) {
        String title = Component.translatable("mcphone.app.chat").getString();
        title = ChatUi.truncate(font, title, w - 28, ChatLayout.TEXT_SCALE);
        ChatUi.buttonText(g, font, title, x + w / 2f,
                y + ChatLayout.HEADER_HEIGHT / 2f, ChatLayout.TEXT_SCALE, FontPalette.title());
        int plusX = x + w - 10;
        plusBounds = new ChatLayout.Rect(plusX - 2, y, 12, ChatLayout.HEADER_HEIGHT);
        boolean plusHovered = plusBounds.contains(mouseX, mouseY);
        // 恢复原来的透明图标入口，不给加号额外套框。
        ChatUi.sizedIcon(g, ChatUi.Icon.ADD, plusBounds.x() + (plusBounds.width() - ChatLayout.ICON_SIZE) / 2f,
                y + (ChatLayout.HEADER_HEIGHT - ChatLayout.ICON_SIZE) / 2f, ChatLayout.ICON_SIZE,
                plusHovered || hasContextMenu() ? ChatUi.accent() : FontPalette.body());
        y += ChatLayout.HEADER_HEIGHT + ChatLayout.SECTION_GAP;
        search.render(g, font, new ChatLayout.Rect(x, y, w, ChatLayout.SEARCH_HEIGHT), mouseX, mouseY, 0);
        return y + ChatLayout.SEARCH_HEIGHT + ChatLayout.SECTION_GAP;
    }

    private void renderEmpty(GuiGraphics g, Font font, int x, int y, int w) {
        ChatUi.text(g, font, Component.translatable(search.query().isBlank() ? "mcphone.chat.empty" : "mcphone.chat.contacts.search_empty").getString(),
                x, y, ChatLayout.TEXT_SCALE, FontPalette.subtle());
        if (!search.query().isBlank()) return;
        y += ChatUi.lineHeight(font, ChatLayout.TEXT_SCALE) + 2;

        for (var line : font.split(Component.translatable("mcphone.chat.empty_hint"),
                ChatLayout.unscaledWidth(w, ChatLayout.TEXT_SCALE))) {
            ChatUi.text(g, font, line, x, y, ChatLayout.TEXT_SCALE, FontPalette.dim());
            y += ChatUi.lineHeight(font, ChatLayout.TEXT_SCALE);
        }
    }

    private void renderRows(GuiGraphics g, Font font, List<Row> list,
                            int x, int y, int w, int bottom, int mouseX, int mouseY) {
        final int rowH = rowHeight(font);
        hoveredPeer = null; hoveredGroup = false;
        teleportHoveredPeer = null;

        for (int i = scrollOffset; i < list.size(); i++) {
            if (y + rowH > bottom) break;

            var row = list.get(i); ConversationSummary c = row.summary();
            boolean hovered = !hasContextMenu() && ChatLayout.hit(mouseX, mouseY, x, y, w, rowH);
            if (hovered) { hoveredPeer = c.id(); hoveredGroup = row.group() != null; }
            ChatUi.glass(g, ChatGlass.Surface.CARD, x, y, w, rowH, hovered);

            if (renderRow(g, font, c, row.group(), x, y, w, mouseX, mouseY)) teleportHoveredPeer = c.id();
            y += rowH + ChatLayout.CARD_GAP;
        }
    }

    /** 返回鼠标是否停在这一行的传送图标上 */
    private boolean renderRow(GuiGraphics g, Font font, ConversationSummary c, GroupSummary group,
                              int x, int y, int w, int mouseX, int mouseY) {
        // 时间在右上、未读在右下；头像不再被数字压住。
        String right = GuiUtil.formatTime(c.lastTime());
        int rightW = ChatUi.width(font, right, ChatLayout.META_SCALE);
        // 传送图标只给在线的人。这里读的是【服务端】的开关 —— 它读得到，靠的是加载器
        // 把 SERVER 档的配置推给客户端；哪些目标做得到，见 versions/targets.json 的
        // server_config_sync。读错了只是图标显隐不对，真正的拦截在 TeleportService。
        boolean canTeleport = group == null && c.online() && ServerConfig.clientAllowFriendTeleport();
        String unread = c.unread() > 0 ? unreadLabel(c.unread()) : "";
        int badgeW = unread.isEmpty() ? 0 : Math.max(ChatLayout.UNREAD_HEIGHT, UnreadBadge.width(font, unread));
        var layout = ChatLayout.card(w, ChatUi.lineHeight(font, ChatLayout.TEXT_SCALE),
                ChatUi.lineHeight(font, ChatLayout.META_SCALE), rightW, badgeW, canTeleport);
        var frame = layout.avatarFrame();
        ChatUi.glass(g, ChatGlass.Surface.AVATAR, x + frame.x(), y + frame.y(),
                frame.width(), frame.height(), false);
        if (group == null) PlayerAvatar.draw(g, c.id(), x + frame.x() + ChatLayout.AVATAR_FRAME,
                y + frame.y() + ChatLayout.AVATAR_FRAME, AVATAR_SIZE);
        else ChatGroupAvatar.draw(g, group, x + frame.x() + ChatLayout.AVATAR_FRAME,
                y + frame.y() + ChatLayout.AVATAR_FRAME, AVATAR_SIZE);
        // 小状态方点落在外框边角，不再用一大块黑色描边遮住脸部。
        int dot = ChatLayout.AVATAR_STATUS_SIZE;
        int dotX = x + frame.x() + frame.width() - dot;
        int dotY = y + frame.y() + frame.height() - dot;
        if (group == null) g.fill(dotX, dotY, dotX + dot, dotY + dot,
                c.online() ? PlayerAvatar.COLOR_ONLINE : PlayerAvatar.COLOR_OFFLINE);

        int nameX = x + layout.textX();
        String name = ChatUi.truncate(font, c.name(), layout.nameWidth(), ChatLayout.TEXT_SCALE);
        ChatUi.text(g, font, name, nameX, y + layout.nameY(), ChatLayout.TEXT_SCALE, colorName());
        boolean tpHovered = !hasContextMenu() && canTeleport && layout.teleportHit().contains(mouseX - x, mouseY - y);

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
            if (group != null && group.last().isPresent()) preview = group.last().get().senderName() + ": " + preview;
            previewColor = colorPreview();
        }
        ChatUi.preview(g, font, preview, nameX, y + layout.previewY(), layout.previewWidth(), previewColor);

        if (canTeleport) renderTeleportIcon(g, font, x, y, layout, tpHovered);

        return tpHovered;
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (contextMouseClicked(mx, my, button)) return true;
        if (button != 0) return false;
        if (search.mouseClicked(mx, my, button)) return true;

        if (plusBounds != null && plusBounds.contains(mx, my)) {
            homeMenu.open(plusBounds.x() + plusBounds.width(), plusBounds.y() + plusBounds.height() + 3);
            return true;
        }
        if (navigation.click(mx, my, button) == ChatNavigationBar.Page.PROFILE) { pendingProfile=true;return true; }
        if (navigation.click(mx, my, button) == ChatNavigationBar.Page.CONTACTS) {
            pendingContacts = true;
            return true;
        }

        // 必须先判传送：图标的点击区整个落在那一行里面，后判会连带打开会话
        if (teleportHoveredPeer != null) {
            MCphoneNetwork.sendToServer(new TeleportToFriendPacket(teleportHoveredPeer));
            pendingClose = true;
            return true;
        }

        if (hoveredPeer != null) {
            if (hoveredGroup) pendingGroup = hoveredPeer; else pendingOpen = hoveredPeer;
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double scrollY) {
        if (dismissContextMenu()) return true;
        if (scrollY > 0 && scrollOffset > 0) {
            scrollOffset--;
            return true;
        }
        if (scrollY < 0 && scrollOffset < rows().size() - 1) {
            scrollOffset++;
            return true;
        }
        return false;
    }

    public boolean keyPressed(int key, int scan, int modifiers) {
        boolean handled = search.keyPressed(key, scan, modifiers); invalidateSearchHits(); return handled;
    }
    public boolean charTyped(char c, int modifiers) {
        boolean handled = search.charTyped(c, modifiers); invalidateSearchHits(); return handled;
    }
    private void invalidateSearchHits() {
        // 输入发生在两次绘制之间，旧行的悬停不能触发打开或传送。
        if (!rowQuery.equals(search.query())) { hoveredPeer = teleportHoveredPeer = null; hoveredGroup = false; }
    }

    /** 传送图标：悬停先铺高亮，再画贴图，没有贴图就画 → 字符兜底 */
    private static void renderTeleportIcon(GuiGraphics g, Font font,
                                           int x, int y, ChatLayout.Card layout, boolean hovered) {
        // 图标保持透明底；点击区域继续使用原布局，去框后不缩小操作范围。
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
        GroupTransport.directory();
    }
    private List<Row> rows() {
        var friends = ChatClientCache.getConversations(); var groups = GroupClientCache.groups();
        String query = search.query();
        if (friends == rowFriends && groups == rowGroups && rowQuery.equals(query)) return cachedRows;
        if (!rowQuery.equals(query)) scrollOffset = 0;
        rowQuery = query;
        rowFriends = friends; rowGroups = groups;
        var out = new java.util.ArrayList<Row>();
        friends.stream().filter(c -> ChatContactDirectory.matches(c.name(), query)).forEach(c -> out.add(new Row(c, null)));
        groups.stream().filter(group -> ChatContactDirectory.matches(group.name(), query)).forEach(group -> out.add(new Row(new ConversationSummary(group.id(), group.name(), false,
                group.last().map(m -> m.message().body()), group.lastTime(), group.unread()), group)));
        out.sort(java.util.Comparator.comparingLong((Row row) -> row.summary().lastTime()).reversed());
        cachedRows = List.copyOf(out); return cachedRows;
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
