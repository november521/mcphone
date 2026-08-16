package com.november.mcphone.feature.chat.client;

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
 * 加联系人界面 —— 列出当前在线的玩家，点一下加为联系人。
 *
 * 为什么从在线玩家里选而不是打名字：Minecraft 里输入法的候选框看不见
 * （理由详见 DeviceNameEditor 类注释），打中文基本是盲打，玩家名又
 * 一个字都错不得。点选完全绕开了这件事，也不会打错字。
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

    /** 头像边长，与会话列表一致 */
    private static final int AVATAR_SIZE = 16;

    /** 头像与名字之间的空隙 */
    private static final int AVATAR_GAP = 3;

    private static final int COLOR_NAME = PhoneTheme.FONT_COLOR_TITLE;
    private static final int COLOR_ADD = PhoneTheme.FONT_COLOR_CONFIRM;
    private static final int COLOR_ACCEPT = PhoneTheme.FONT_COLOR_ARMED;
    private static final int COLOR_PENDING = PhoneTheme.FONT_COLOR_SUBTLE;
    private static final int COLOR_REMOVE = PhoneTheme.FONT_COLOR_DANGER;
    private static final int COLOR_ROW_HOVER = PhoneTheme.COLOR_ROW_HOVER;

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
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 4;

        List<OnlinePlayer> players = ChatClientCache.getOnlinePlayers();

        // 被截断时必须说出来，否则玩家以为服务器就这么些人
        if (ChatClientCache.isOnlineListTruncated()) {
            for (var line : font.split(Component.translatable("mcphone.chat.truncated",
                    players.size(), ChatClientCache.getTotalOnline()), w)) {
                g.drawString(font, line, x, y, PhoneTheme.FONT_COLOR_NOTICE, false);
                y += font.lineHeight;
            }
            y += 2;
        }

        if (players.isEmpty()) {
            for (var line : font.split(Component.translatable("mcphone.chat.online_empty"), w)) {
                g.drawString(font, line, x, y, PhoneTheme.FONT_COLOR_SUBTLE, false);
                y += font.lineHeight;
            }
            hoveredIdx = -1;
            return;
        }

        clampScroll(players.size(), bottom - y);
        renderRows(g, font, players, x, y, w, bottom, mouseX, mouseY);
    }

    private void renderRows(GuiGraphics g, Font font, List<OnlinePlayer> players,
                            int x, int y, int w, int bottom, int mouseX, int mouseY) {
        final int rowH = rowHeight();
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

            // 本界面列的全是在线玩家，状态点画了也全是绿的，纯属占地方
            int avatarY = y + (rowH - AVATAR_SIZE) / 2;
            PlayerAvatar.draw(g, p.id(), x, avatarY, AVATAR_SIZE);

            int nameX = x + AVATAR_SIZE + AVATAR_GAP;
            int textY = y + (rowH - font.lineHeight) / 2;

            // 名字按剩余宽度截断，否则长名字会盖住右侧的动作文字
            String name = GuiUtil.truncate(font, p.name(), w - (nameX - x) - actionW - 6);
            g.drawString(font, name, nameX, textY, COLOR_NAME, false);
            g.drawString(font, action, x + w - actionW - 2, textY,
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

    /**
     * 行高由头像决定，不再由文字决定。
     *
     * 16 的头像塞不进原先 14 像素的行，故加到 18，一屏从 10 行减到 8 行。
     * 宁可少两行也不用 12×12 的头像：那是 1.5 倍放大，像素会毛糙。
     */
    private static int rowHeight() {
        return AVATAR_SIZE + 2;
    }

    /** 有人下线导致列表变短时必须夹紧，否则会滚到空白处 */
    private void clampScroll(int total, int availableHeight) {
        int visible = Math.max(1, availableHeight / rowHeight());
        int maxOffset = Math.max(0, total - visible);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
        if (scrollOffset < 0) scrollOffset = 0;
    }

}
