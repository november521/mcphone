package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.ServerConfig;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.client.PlayerAvatar;
import com.november.mcphone.feature.chat.net.ChatClientCache;
import com.november.mcphone.feature.chat.net.ConversationSummary;
import com.november.mcphone.feature.chat.net.RequestConversationsPacket;
import com.november.mcphone.feature.chat.net.TeleportToFriendPacket;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

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

    private static int colorName() { return FontPalette.title(); }
    private static int colorPreview() { return FontPalette.preview(); }
    private static int colorTime() { return FontPalette.timestamp(); }
    private static final int COLOR_UNREAD_BG = PhoneTheme.COLOR_UNREAD_BADGE;
    private static final int COLOR_ROW_HOVER = PhoneTheme.COLOR_ROW_HOVER;

    private long lastRequestMs;
    private int scrollOffset;
    private boolean addContactHovered;

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
        final int bottom = phoneTop + screenH - navH;
        int y = phoneTop + statusH + 4;

        y = renderHeader(g, font, x, y, w, mouseX, mouseY);

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
        g.drawString(font, Component.translatable("mcphone.app.chat").getString(),
                x, y, FontPalette.title(), true);

        String plus = "+";
        int plusW = font.width(plus);
        int plusX = x + w - plusW - 2;
        addContactHovered = mouseX >= plusX - 3 && mouseX <= plusX + plusW + 3
                         && mouseY >= y - 2 && mouseY <= y + font.lineHeight + 2;
        g.drawString(font, plus, plusX, y,
                addContactHovered ? FontPalette.title() : FontPalette.link(), true);

        y += font.lineHeight + 4;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        return y + 4;
    }

    private void renderEmpty(GuiGraphics g, Font font, int x, int y, int w) {
        g.drawString(font, Component.translatable("mcphone.chat.empty").getString(),
                x, y, FontPalette.subtle(), false);
        y += font.lineHeight + 2;

        for (var line : font.split(Component.translatable("mcphone.chat.empty_hint"), w)) {
            g.drawString(font, line, x, y, FontPalette.dim(), false);
            y += font.lineHeight;
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

        String right = c.unread() > 0 ? unreadLabel(c.unread()) : GuiUtil.formatTime(c.lastTime());
        int rightW = right.isEmpty() ? 0 : font.width(right) + (c.unread() > 0 ? 4 : 0);

        int nameX = x + AVATAR_SIZE + AVATAR_GAP;
        int nameMaxW = w - (nameX - x) - rightW - 4;
        String name = GuiUtil.truncate(font, c.name(), nameMaxW);
        g.drawString(font, name, nameX, y, colorName(), false);

        // 传送图标只给在线的人；ServerConfig 会同步到客户端，这里读得到。
        // 位置先算、图最后画：第二行预览要按它让出的宽度截断
        boolean canTeleport = c.online() && ServerConfig.allowFriendTeleport();
        int secondLineY = y + font.lineHeight + 1;
        int tpW = 0;
        int tpX = 0;
        int tpY = 0;
        boolean tpHovered = false;
        if (canTeleport) {
            // 往里缩 TP_HIT_PAD：悬停高亮铺的是点击区，贴边会凸出整行高亮
            tpW = TP_ICON_SIZE + TP_HIT_PAD + TP_GAP;
            tpX = x + w - TP_ICON_SIZE - TP_HIT_PAD;
            tpY = secondLineY + (font.lineHeight - TP_ICON_SIZE) / 2;
            tpHovered = GuiUtil.hit(mouseX, mouseY,
                    tpX - TP_HIT_PAD, tpY - TP_HIT_PAD,
                    TP_ICON_SIZE + TP_HIT_PAD * 2, TP_ICON_SIZE + TP_HIT_PAD * 2);
        }

        if (!right.isEmpty()) {
            int rx = x + w - rightW;
            if (c.unread() > 0) {
                PhoneSkin.drawOrFill(g, PhoneSkin.Element.UNREAD_BADGE,
                        rx - 1, y - 1, x + w - (rx - 1), font.lineHeight + 1, COLOR_UNREAD_BG);
                g.drawString(font, right, rx + 1, y, PhoneTheme.FONT_COLOR_BADGE, false);
            } else {
                g.drawString(font, right, rx, y, colorTime(), false);
            }
        }

        // 第二行：消息预览，停在传送图标上时改说传送提示
        String preview;
        int previewColor;
        if (tpHovered) {
            preview = Component.translatable("mcphone.chat.teleport_hint").getString();
            previewColor = FontPalette.link();
        } else {
            preview = c.lastText().isEmpty()
                    ? Component.translatable("mcphone.chat.no_message").getString()
                    : c.lastText();
            previewColor = colorPreview();
        }
        g.drawString(font, GuiUtil.truncate(font, preview, w - (nameX - x) - tpW),
                nameX, secondLineY, previewColor, false);

        if (canTeleport) renderTeleportIcon(g, font, tpX, tpY, tpHovered);

        return tpHovered;
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        if (addContactHovered) {
            pendingAddContact = true;
            return true;
        }

        // 必须先判传送：图标的点击区整个落在那一行里面，后判会连带打开会话
        if (teleportHoveredPeer != null) {
            PacketDistributor.sendToServer(new TeleportToFriendPacket(teleportHoveredPeer));
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
        PacketDistributor.sendToServer(new RequestConversationsPacket());
    }

    private static int rowHeight(Font font) {
        return font.lineHeight * 2 + 4;
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
