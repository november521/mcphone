package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.client.PlayerAvatar;
import com.november.mcphone.feature.chat.net.ChatClientCache;
import com.november.mcphone.feature.chat.net.FriendRequestPacket;
import com.november.mcphone.feature.chat.net.OnlinePlayer;
import com.november.mcphone.feature.chat.net.Relation;
import com.november.mcphone.feature.chat.net.RemoveFriendPacket;
import com.november.mcphone.feature.chat.net.RequestOnlinePlayersPacket;
import com.november.mcphone.feature.chat.net.RespondFriendRequestPacket;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 加联系人界面：列出在线玩家，点一下加为联系人。
 * 所有操作只发包、不改本地状态，按钮以服务端回发的列表为准。
 * 悬停记的是整条 OnlinePlayer 而不是行号：列表每 3 秒被整份换掉，按下标重查会点到别人。
 */
public final class ChatAddContact {

    private static final int PAD = 4;

    private static final long REFRESH_INTERVAL_MS = 3000L;

    private static final int AVATAR_SIZE = 16;

    private static final int AVATAR_GAP = 3;

    private static int colorName() { return FontPalette.title(); }
    private static int colorAdd() { return FontPalette.confirm(); }
    private static int colorAccept() { return FontPalette.armed(); }
    private static int colorPending() { return FontPalette.subtle(); }
    private static int colorRemove() { return FontPalette.danger(); }
    private static final int COLOR_ROW_HOVER = PhoneTheme.COLOR_ROW_HOVER;

    private long lastRequestMs;
    private int scrollOffset;

    /** 鼠标停在哪一条上（连同它当时的关系），null 表示没有。理由见类注释 */
    private OnlinePlayer hovered;

    public void open() {
        scrollOffset = 0;
        hovered = null;
        lastRequestMs = 0L;   // 置零＝下一帧立即请求
    }

    public void close() {
        hovered = null;
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        maybeRefresh();

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;
        final int bottom = phoneTop + screenH - navH;
        int y = phoneTop + statusH + 4;

        g.drawString(font, Component.translatable("mcphone.chat.add_contact").getString(),
                x, y, FontPalette.title(), true);
        y += font.lineHeight + 4;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 4;

        List<OnlinePlayer> players = ChatClientCache.getOnlinePlayers();

        if (ChatClientCache.isOnlineListTruncated()) {
            for (var line : font.split(Component.translatable("mcphone.chat.truncated",
                    players.size(), ChatClientCache.getTotalOnline()), w)) {
                g.drawString(font, line, x, y, FontPalette.notice(), false);
                y += font.lineHeight;
            }
            y += 2;
        }

        if (players.isEmpty()) {
            for (var line : font.split(Component.translatable("mcphone.chat.online_empty"), w)) {
                g.drawString(font, line, x, y, FontPalette.subtle(), false);
                y += font.lineHeight;
            }
            hovered = null;
            return;
        }

        clampScroll(players.size(), bottom - y);
        renderRows(g, font, players, x, y, w, bottom, mouseX, mouseY);
    }

    private void renderRows(GuiGraphics g, Font font, List<OnlinePlayer> players,
                            int x, int y, int w, int bottom, int mouseX, int mouseY) {
        final int rowH = rowHeight();
        hovered = null;

        for (int i = scrollOffset; i < players.size(); i++) {
            if (y + rowH > bottom) break;

            OnlinePlayer p = players.get(i);
            boolean isHovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + rowH;
            if (isHovered) {
                hovered = p;
                g.fill(x, y, x + w, y + rowH, COLOR_ROW_HOVER);
            }

            String action = Component.translatable(actionKey(p.relation())).getString();
            int actionW = font.width(action);

            // 全是在线玩家，不画状态点
            int avatarY = y + (rowH - AVATAR_SIZE) / 2;
            PlayerAvatar.draw(g, p.id(), x, avatarY, AVATAR_SIZE);

            int nameX = x + AVATAR_SIZE + AVATAR_GAP;
            int textY = y + (rowH - font.lineHeight) / 2;

            String name = GuiUtil.truncate(font, p.name(), w - (nameX - x) - actionW - 6);
            g.drawString(font, name, nameX, textY, colorName(), false);
            g.drawString(font, action, x + w - actionW - 2, textY,
                    actionColor(p.relation()), false);

            y += rowH;
        }
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        OnlinePlayer p = hovered;
        if (p == null) return false;

        switch (p.relation()) {
            case NONE -> PacketDistributor.sendToServer(new FriendRequestPacket(p.id()));
            case REQUEST_RECEIVED ->
                    PacketDistributor.sendToServer(new RespondFriendRequestPacket(p.id(), true));
            case FRIEND -> PacketDistributor.sendToServer(new RemoveFriendPacket(p.id()));
            // 已发出的申请只能等对方处理，重复发包没用
            case REQUEST_SENT -> { }
        }
        return true;
    }

    private static String actionKey(Relation relation) {
        return switch (relation) {
            case NONE -> "mcphone.chat.add_action";
            case REQUEST_SENT -> "mcphone.chat.request_pending";
            case REQUEST_RECEIVED -> "mcphone.chat.accept_action";
            case FRIEND -> "mcphone.chat.remove_action";
        };
    }

    private static int actionColor(Relation relation) {
        return switch (relation) {
            case NONE -> colorAdd();
            case REQUEST_SENT -> colorPending();
            case REQUEST_RECEIVED -> colorAccept();
            case FRIEND -> colorRemove();
        };
    }

    public boolean mouseScrolled(double scrollY) {
        if (scrollY > 0 && scrollOffset > 0) {
            scrollOffset--;
            return true;
        }
        if (scrollY < 0 && scrollOffset < ChatClientCache.getOnlinePlayers().size() - 1) {
            scrollOffset++;
            return true;
        }
        return false;
    }

    private void maybeRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < REFRESH_INTERVAL_MS) return;

        lastRequestMs = now;
        PacketDistributor.sendToServer(new RequestOnlinePlayersPacket());
    }

    private static int rowHeight() {
        return AVATAR_SIZE + 2;
    }

    private void clampScroll(int total, int availableHeight) {
        int visible = Math.max(1, availableHeight / rowHeight());
        int maxOffset = Math.max(0, total - visible);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
        if (scrollOffset < 0) scrollOffset = 0;
    }

}
