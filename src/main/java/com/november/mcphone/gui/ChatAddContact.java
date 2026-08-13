package com.november.mcphone.gui;

import com.november.mcphone.network.chat.ChatClientCache;
import com.november.mcphone.network.chat.FriendRequestPacket;
import com.november.mcphone.network.chat.OnlinePlayer;
import com.november.mcphone.network.chat.Relation;
import com.november.mcphone.network.chat.RemoveFriendPacket;
import com.november.mcphone.network.chat.RequestOnlinePlayersPacket;
import com.november.mcphone.network.chat.RespondFriendRequestPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 加联系人界面 —— 列出当前在线的玩家，点一下加为联系人。
 *
 * 为什么从在线玩家里选而不是打名字：Minecraft 的 GUI 不支持输入法，
 * 中文名只能靠粘贴。点选完全绕开了这个问题，也不会打错字。
 *
 * 好友是双向的，所以同一行要表达四种状态：
 *   陌生人      → "+ 添加"，点了发出申请
 *   已发出申请  → "已申请"，灰色不可点，等对方处理
 *   收到对方申请 → "✔ 同意"，点了直接成为好友
 *   已是好友    → "✕ 解除"
 *
 * 一个布尔量表达不了这四种，故服务端下发的是 {@link Relation}。
 *
 * 所有操作都只发包、不改本地状态：服务端处理完会回发新的在线列表，
 * 按钮状态以那份为准。本地抢先改的话，一旦服务端因为超上限拒绝了，
 * 界面就会显示成功的假象。
 */
public final class ChatAddContact {

    private static final int PAD = 4;

    /** 刷新间隔：玩家会上下线，列表得跟着变 */
    private static final long REFRESH_INTERVAL_MS = 3000L;

    private static final int COLOR_NAME = 0xFFFFFFFF;
    private static final int COLOR_ADD = 0xFF66FF88;
    private static final int COLOR_ACCEPT = 0xFFFFDD44;
    private static final int COLOR_PENDING = 0xFF888888;
    private static final int COLOR_REMOVE = 0xFFFF8888;
    private static final int COLOR_ROW_HOVER = 0x33FFFFFF;

    private long lastRequestMs;
    private int scrollOffset;
    private int hoveredIdx = -1;

    public void open() {
        scrollOffset = 0;
        hoveredIdx = -1;
        lastRequestMs = 0L;   // 置零＝下一帧立即请求
    }

    public void close() {
        hoveredIdx = -1;
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

        g.drawString(font, Component.translatable("mcphone.chat.add_contact").getString(),
                x, y, PhoneTheme.FONT_COLOR_TITLE, true);
        y += font.lineHeight + 4;
        g.fill(x, y, x + w, y + 1, 0x44FFFFFF);
        y += 4;

        List<OnlinePlayer> players = ChatClientCache.getOnlinePlayers();

        // 被截断时必须说出来，否则玩家以为服务器就这么些人
        if (ChatClientCache.isOnlineListTruncated()) {
            for (var line : font.split(Component.translatable("mcphone.chat.truncated",
                    players.size(), ChatClientCache.getTotalOnline()), w)) {
                g.drawString(font, line, x, y, 0xFFFFAA44, false);
                y += font.lineHeight;
            }
            y += 2;
        }

        if (players.isEmpty()) {
            for (var line : font.split(Component.translatable("mcphone.chat.online_empty"), w)) {
                g.drawString(font, line, x, y, 0xFF888888, false);
                y += font.lineHeight;
            }
            hoveredIdx = -1;
            return;
        }

        clampScroll(players.size(), bottom - y, font);
        renderRows(g, font, players, x, y, w, bottom, mouseX, mouseY);
    }

    private void renderRows(GuiGraphics g, Font font, List<OnlinePlayer> players,
                            int x, int y, int w, int bottom, int mouseX, int mouseY) {
        final int rowH = rowHeight(font);
        hoveredIdx = -1;

        for (int i = scrollOffset; i < players.size(); i++) {
            if (y + rowH > bottom) break;

            OnlinePlayer p = players.get(i);
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + rowH;
            if (hovered) {
                hoveredIdx = i;
                g.fill(x, y, x + w, y + rowH, COLOR_ROW_HOVER);
            }

            String action = Component.translatable(actionKey(p.relation())).getString();
            int actionW = font.width(action);

            // 名字按剩余宽度截断，否则长名字会盖住右侧的动作文字
            String name = truncate(font, p.name(), w - actionW - 8);
            g.drawString(font, name, x + 2, y + 2, COLOR_NAME, false);
            g.drawString(font, action, x + w - actionW - 2, y + 2,
                    actionColor(p.relation()), false);

            y += rowH;
        }
    }

    // ============================================================
    //  鼠标
    // ============================================================

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        List<OnlinePlayer> players = ChatClientCache.getOnlinePlayers();
        if (hoveredIdx < 0 || hoveredIdx >= players.size()) return false;

        OnlinePlayer p = players.get(hoveredIdx);
        // 只发包，不改本地状态：服务端会回发新列表，按钮以那份为准
        switch (p.relation()) {
            case NONE -> PacketDistributor.sendToServer(new FriendRequestPacket(p.id()));
            case REQUEST_RECEIVED ->
                    PacketDistributor.sendToServer(new RespondFriendRequestPacket(p.id(), true));
            case FRIEND -> PacketDistributor.sendToServer(new RemoveFriendPacket(p.id()));
            // 已发出的申请只能等对方处理，点了不做任何事——
            // 重复发包既没用，又会让服务端白跑一遍校验
            case REQUEST_SENT -> { }
        }
        return true;
    }

    /** 每种关系对应的按钮文案 */
    private static String actionKey(Relation relation) {
        return switch (relation) {
            case NONE -> "mcphone.chat.add_action";
            case REQUEST_SENT -> "mcphone.chat.request_pending";
            case REQUEST_RECEIVED -> "mcphone.chat.accept_action";
            case FRIEND -> "mcphone.chat.remove_action";
        };
    }

    /** 待处理的申请用灰色：它不可点，颜色要说明这一点 */
    private static int actionColor(Relation relation) {
        return switch (relation) {
            case NONE -> COLOR_ADD;
            case REQUEST_SENT -> COLOR_PENDING;
            case REQUEST_RECEIVED -> COLOR_ACCEPT;
            case FRIEND -> COLOR_REMOVE;
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

    // ============================================================
    //  内部
    // ============================================================

    private void maybeRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < REFRESH_INTERVAL_MS) return;

        lastRequestMs = now;
        PacketDistributor.sendToServer(new RequestOnlinePlayersPacket());
    }

    private static int rowHeight(Font font) {
        return font.lineHeight + 5;
    }

    /** 有人下线导致列表变短时必须夹紧，否则会滚到空白处 */
    private void clampScroll(int total, int availableHeight, Font font) {
        int visible = Math.max(1, availableHeight / rowHeight(font));
        int maxOffset = Math.max(0, total - visible);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
        if (scrollOffset < 0) scrollOffset = 0;
    }

    private static String truncate(Font font, String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
    }
}
