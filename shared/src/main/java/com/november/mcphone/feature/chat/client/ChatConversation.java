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
import net.minecraft.client.gui.screens.Screen;
import com.november.mcphone.feature.chat.client.messageaction.ChatMessageActions;
import com.november.mcphone.feature.chat.TextBody;
import com.november.mcphone.feature.chat.net.DeleteChatMessagePacket;
import net.minecraft.network.chat.Component;

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
    private final ChatMessageActions actions = new ChatMessageActions(
            (g, x, y, w, h) -> ChatUi.glass(g, ChatGlass.Surface.MENU, x, y, w, h, false),
            text -> Minecraft.getInstance().keyboardHandler.setClipboard(text),
            (target, message, request) -> {
                ChatClientCache.beginDeletion(target, message, request);
                MCphoneNetwork.sendToServer(new DeleteChatMessagePacket(target, message, request));
            }, ChatClientCache::cancelDeletion,
            key -> { var player = Minecraft.getInstance().player;
                if (player != null) player.displayClientMessage(Component.translatable(key), true); });
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
        actions.reset();
        messages.reset();
        viewer.dismiss();
        composer.reset(focused);
        backHovered = pendingBack = false;
    }

    public UUID peer() { return peer; }
    public boolean isViewing(UUID other) { return peer != null && peer.equals(other); }
    public boolean dismissViewer() { return viewer.dismiss(); }
    public boolean hasContextMenu() { return actions.isOpen(); }
    public boolean dismissContextMenu() { return actions.dismiss(); }
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
        var receipt = ChatClientCache.consumeDeleteResult();
        if (receipt != null) actions.result(receipt.request(), receipt.result());
        actions.tick(System.currentTimeMillis());
        actions.reconcile(ChatClientCache.getMessages());
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
        actions.render(g, font, phoneLeft + PAD, phoneTop + statusH + ChatLayout.TOP_GAP,
                screenW - PAD * 2, screenH - statusH - navH - ChatLayout.TOP_GAP * 2, mouseX, mouseY);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (peer == null) return false;
        if (viewer.mouseClicked(button)) return true;
        if (actions.mouseClicked(mx, my, button, System.currentTimeMillis())) return true;
        if (button == 1) {
            ChatMessage target = messages.messageAt(mx, my);
            if (target != null) {
                messages.prepareMenu(target.id());
                composer.dismissAttachmentMenu();
                composer.blur();
                actions.open(peer, target.id(), target.body() instanceof TextBody text ? text.text() : null,
                        messages.selectedText(target.id()), mx, my);
            }
            return true;
        }
        if (button == 0 && backHovered) { pendingBack = true; return true; }
        if (composer.mouseClicked(button)) { messages.clearSelection(); return true; }
        UUID image = button == 0 ? messages.imageAt(mx, my) : null;
        if (image != null) {
            messages.clearSelection();
            composer.blur();
            viewer.open(image);
            return true;
        }
        if (button == 0 && messages.mouseClicked(mx, my)) { composer.blur(); return true; }
        composer.focusInput(mx, my, button);
        return false;
    }

    public boolean mouseDragged(double mx, double my, int button) {
        return peer != null && (viewer.isOpen() || actions.isOpen() || messages.mouseDragged(mx, my, button));
    }
    public boolean mouseReleased(double mx, double my, int button) {
        return peer != null && (viewer.isOpen() || actions.isOpen() || messages.mouseReleased(mx, my, button));
    }

    /** 查看器和消息菜单是模态层；隐藏的输入框不能继续打字或按 Enter 发出草稿。 */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (peer == null) return false;
        if (viewer.isOpen() || actions.isOpen()) return true;
        if (Screen.isCopy(keyCode) && !messages.selectedText().isEmpty()) {
            Minecraft.getInstance().keyboardHandler.setClipboard(messages.selectedText());
            return true;
        }
        return composer.keyPressed(keyCode, scanCode, modifiers);
    }
    public boolean charTyped(char c, int modifiers) {
        return peer != null && (viewer.isOpen() || actions.isOpen() || composer.charTyped(c, modifiers));
    }
    public boolean mouseScrolled(double amount) {
        if (peer == null) return false;
        if (actions.dismiss()) return true;
        return viewer.isOpen() || messages.mouseScrolled(amount);
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
