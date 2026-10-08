package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.feature.chat.ChatMessage;
import com.november.mcphone.feature.chat.net.ChatClientCache;
import com.november.mcphone.feature.chat.net.ConversationSummary;
import com.november.mcphone.feature.chat.net.MarkReadPacket;
import com.november.mcphone.feature.chat.net.RequestConversationsPacket;
import com.november.mcphone.feature.chat.net.RequestMessagesPacket;
import com.november.mcphone.feature.chat.net.SendChatMessagePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.UUID;

/** 单个会话的协调者：生命周期、标题、已读/摘要请求和输入分发。组件各自持有页面内状态。 */
public final class ChatConversation {
    private static final int PAD = 4;
    private static final int INPUT_GAP = 2;
    private static final long REFRESH_INTERVAL_MS = 3000L;
    private final ChatMessagePane messages = new ChatMessagePane();
    private final ChatImageViewer viewer = new ChatImageViewer();
    private final ChatComposer composer = new ChatComposer(this::send);
    private UUID peer;
    private long lastRequestMs;
    private List<ChatMessage> markedFrom;
    private boolean backHovered, pendingBack;

    public void open(UUID peer) {
        this.peer = peer;
        lastRequestMs = System.currentTimeMillis();
        resetComponents(true);
        // 先绑定对端再拉历史，否则抢先到达的推送会被缓存丢弃。
        ChatClientCache.openConversation(peer);
        markedFrom = ChatClientCache.getMessages();
        MCphoneNetwork.sendToServer(new RequestMessagesPacket(peer));
    }

    public void close() {
        peer = null;
        markedFrom = null;
        resetComponents(false);
        ChatImageCache.beginFrame(null);
        ChatClientCache.closeConversation();
    }

    private void resetComponents(boolean focused) {
        messages.reset();
        viewer.dismiss();
        composer.reset(focused);
        backHovered = pendingBack = false;
    }

    public UUID peer() { return peer; }
    public boolean isViewing(UUID other) { return peer != null && peer.equals(other); }
    public boolean dismissViewer() { return viewer.dismiss(); }
    public ChatAttachment consumeAttachRequest() { return composer.consumeAttachRequest(); }
    public boolean consumeBackRequest() {
        boolean out = pendingBack;
        pendingBack = false;
        return out;
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, float partialTick, Font font) {
        if (peer == null) return;
        maybeRefresh();
        maybeMarkRead();
        int x = phoneLeft + PAD, w = screenW - PAD * 2;
        int inputTop = phoneTop + screenH - navH - ChatLayout.INPUT_HEIGHT - INPUT_GAP;
        int top = renderHeader(g, font, x, phoneTop + statusH + ChatLayout.TOP_GAP, w, mouseX, mouseY);
        ChatImageCache.beginFrame(peer);
        messages.render(g, font, x, top, w, inputTop - INPUT_GAP, peer, selfId());
        composer.render(g, font, x, inputTop, w, mouseX, mouseY, partialTick);
        if (viewer.isOpen()) ChatImageCache.beginFrame(peer);
        viewer.render(g, font, phoneLeft, phoneTop + statusH, screenW,
                screenH - statusH - navH, mouseX, mouseY);
        // 查看器遮住消息区时只取放大图；最后统一按当前会话发请求。
        ChatImageCache.flushRequests(peer);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (peer == null) return false;
        if (viewer.mouseClicked(button)) return true;
        if (button == 0 && backHovered) { pendingBack = true; return true; }
        if (composer.mouseClicked(button)) return true;
        UUID image = button == 0 ? messages.imageAt(mx, my) : null;
        if (image != null) { viewer.open(image); return true; }
        composer.focusInput(mx, my, button);
        return false;
    }

    /** 查看器是模态层；隐藏的输入框不能继续打字或按 Enter 发出草稿。 */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return peer != null && (viewer.isOpen() || composer.keyPressed(keyCode, scanCode, modifiers));
    }
    public boolean charTyped(char c, int modifiers) {
        return peer != null && (viewer.isOpen() || composer.charTyped(c, modifiers));
    }
    public boolean mouseScrolled(double amount) {
        return peer != null && (viewer.isOpen() || messages.mouseScrolled(amount));
    }

    /** 服务端回声才插入消息，避免校验拒收后客户端还留下假消息。 */
    private void send(String text) {
        if (peer == null) return;
        MCphoneNetwork.sendToServer(new SendChatMessagePacket(peer, text));
        messages.latest();
    }

    private int renderHeader(GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
        ConversationSummary s = summary();
        backHovered = GuiUtil.hit(mx, my, x, y, 12, ChatLayout.HEADER_HEIGHT);
        int backSize = 7;
        ChatUi.sizedIcon(g, ChatUi.Icon.BACK, x + (12 - backSize) / 2f,
                y + (ChatLayout.HEADER_HEIGHT - backSize) / 2f, backSize,
                backHovered ? FontPalette.title() : FontPalette.link());
        // 更多菜单只留位置；不挂点击动作，也不借用现有附件菜单。
        ChatUi.sizedIcon(g, ChatUi.Icon.MORE, x + w - ChatLayout.ICON_SIZE,
                y + (ChatLayout.HEADER_HEIGHT - ChatLayout.ICON_SIZE) / 2f, ChatLayout.ICON_SIZE, FontPalette.dim());
        String name = ChatUi.truncate(font, peerName(s), w - 30, ChatLayout.TEXT_SCALE);
        ChatUi.centeredText(g, font, name, x + w / 2f,
                y + (ChatLayout.HEADER_HEIGHT - ChatUi.lineHeight(font, ChatLayout.TEXT_SCALE)) / 2,
                ChatLayout.TEXT_SCALE, FontPalette.title());
        y += ChatLayout.HEADER_HEIGHT;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        return y + ChatLayout.SECTION_GAP;
    }

    private void maybeRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < REFRESH_INTERVAL_MS) return;

        lastRequestMs = now;
        MCphoneNetwork.sendToServer(new RequestConversationsPacket());
    }

    private void maybeMarkRead() {
        List<ChatMessage> src = ChatClientCache.getMessages();
        if (src == markedFrom || peer == null) return;

        markedFrom = src;
        MCphoneNetwork.sendToServer(new MarkReadPacket(peer));
    }

    private ConversationSummary summary() {
        if (peer == null) return null;
        for (ConversationSummary c : ChatClientCache.getConversations()) {
            if (c.id().equals(peer)) return c;
        }
        return null;
    }

    private String peerName(ConversationSummary s) {
        if (s != null) return s.name();
        return peer == null ? "" : peer.toString().substring(0, 8);
    }

    private static UUID selfId() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : player.getUUID();
    }
}
