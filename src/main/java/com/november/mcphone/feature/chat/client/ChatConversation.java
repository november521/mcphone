package com.november.mcphone.feature.chat.client;

import net.minecraft.util.Mth;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.client.PlayerAvatar;
import com.november.mcphone.feature.chat.ChatMessage;
import com.november.mcphone.feature.chat.net.ChatClientCache;
import com.november.mcphone.feature.chat.net.ConversationSummary;
import com.november.mcphone.feature.chat.net.MarkReadPacket;
import com.november.mcphone.feature.chat.net.RequestConversationsPacket;
import com.november.mcphone.feature.chat.net.RequestMessagesPacket;
import com.november.mcphone.feature.chat.net.SendChatMessagePacket;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import com.november.mcphone.core.net.MCphoneNetwork;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 单个会话界面：与某位好友的聊天记录。数据全来自 ChatClientCache，历史进入时拉一次，
 * 之后靠服务端推送。消息按气泡宽度折行后整块缓存，只在消息列表换了实例时重排；
 * 滚动以像素计，不以条数计。
 * 输入用原版 EditBox。本界面下所有按键都得被 PhoneScreen 吞掉（打拼音按到 e 会命中
 * 背包键，手机当场关掉），ESC 除外——它是关机键。
 */
public final class ChatConversation {

    private static final int PAD = 4;

    private static final long REFRESH_INTERVAL_MS = 3000L;

    private static final float BUBBLE_MAX_RATIO = 0.72f;

    private static final int BUBBLE_PAD_X = 3;
    private static final int BUBBLE_PAD_Y = 2;

    private static final int BLOCK_GAP = 3;

    private static final int STAMP_PAD_Y = 2;

    /** 相邻两条消息间隔超过这么久，中间才插一行时间 */
    private static final long STAMP_GAP_MS = 5 * 60 * 1000L;

    /** 滚轮一格滚多少像素 */
    private static final int SCROLL_STEP = 18;

    private static final int INPUT_H = 14;

    private static final int INPUT_GAP = 2;

    private static final int INPUT_TEXT_PAD = 3;

    /** 光标不受 EditBox 的文字裁剪，右端要额外留出一个 "_" 的宽度，否则会戳进发送键 */
    private static int cursorRoom(Font font) {
        return font.width("_");
    }

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final int AVATAR_SIZE = 16;

    private static final int AVATAR_GAP = 3;

    private static final int COLOR_BUBBLE_SELF = PhoneTheme.COLOR_CHAT_BUBBLE_SELF;
    private static final int COLOR_BUBBLE_PEER = PhoneTheme.COLOR_CHAT_BUBBLE_PEER;
    private static final int COLOR_TEXT_SELF = PhoneTheme.FONT_COLOR_CHAT_SELF;
    private static final int COLOR_TEXT_PEER = PhoneTheme.FONT_COLOR_CHAT_PEER;
    private static int colorStamp() { return FontPalette.timestamp(); }
    private static int colorEmpty() { return FontPalette.subtle(); }
    private static final int COLOR_INPUT_BG = PhoneTheme.COLOR_CHAT_INPUT_BG;
    private static final int COLOR_SEND = PhoneTheme.FONT_COLOR_CHAT_SEND;
    private static final int COLOR_SEND_HOVER = PhoneTheme.FONT_COLOR_CHAT_SEND_HOVER;

    private static final int COLOR_SEND_OFF = PhoneTheme.FONT_COLOR_CHAT_SEND_OFF;

    /** 当前会话的对端；null 表示不在会话界面 */
    private UUID peer;

    private long lastRequestMs;

    /** 从最新一条往回翻了多少像素。0 ＝ 贴着最新一条 */
    private int scrollPx;

    private int maxScroll;

    /** 首次 render 时才创建（那时才知道机身坐标），之后每帧同步位置 */
    private EditBox box;

    private boolean sendHovered;

    /** 上次上报过已读的那份消息列表，用来发现"又来新消息了" */
    private List<ChatMessage> markedFrom;

    private List<Block> blocks = List.of();
    private int contentH;
    private List<ChatMessage> laidOutFrom;
    private int laidOutWidth = -1;

    /** 排版后的一块：stamp 为 true 是居中的时间戳行，不画气泡；w/h 含内边距 */
    private record Block(boolean stamp, boolean self, List<FormattedCharSequence> lines,
                         int w, int h) {}

    /**
     * 进入会话。必须先 openConversation 再发请求，顺序不能颠倒：
     * 新消息推送可能抢在历史之前到达，缓存不知道对端会直接把它丢掉。
     */
    public void open(UUID peer) {
        this.peer = peer;
        this.scrollPx = 0;
        this.maxScroll = 0;
        // 不置零：会话列表刚拉过摘要，等下一轮定时刷新即可
        this.lastRequestMs = System.currentTimeMillis();

        // 清掉上一个会话的排版，否则会闪出别人的记录
        this.laidOutFrom = null;
        this.blocks = List.of();
        this.contentH = 0;

        if (box != null) {
            box.setValue("");
            box.setFocused(true);
        }

        ChatClientCache.openConversation(peer);
        this.markedFrom = ChatClientCache.getMessages();

        MCphoneNetwork.sendToServer(new RequestMessagesPacket(peer));
    }

    public boolean isViewing(UUID other) {
        return peer != null && peer.equals(other);
    }

    public void close() {
        peer = null;
        laidOutFrom = null;
        blocks = List.of();
        contentH = 0;
        sendHovered = false;
        markedFrom = null;
        if (box != null) box.setFocused(false);
        ChatClientCache.closeConversation();
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, float partialTick, Font font) {

        maybeRefresh();
        maybeMarkRead();

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;

        final int inputTop = phoneTop + screenH - navH - INPUT_H - INPUT_GAP;
        final int bottom = inputTop - INPUT_GAP;

        int y = phoneTop + statusH + 4;
        y = renderHeader(g, font, x, y, w);

        relayout(font, w);
        renderMessages(g, font, x, y, w, bottom);
        renderInputBar(g, font, x, inputTop, w, mouseX, mouseY, partialTick);
    }

    private int renderHeader(GuiGraphics g, Font font, int x, int y, int w) {
        ConversationSummary s = summary();
        boolean online = s != null && s.online();

        // 刚 close 完的那一帧 peer 为空
        if (peer != null) {
            PlayerAvatar.drawWithStatus(g, peer, x, y, AVATAR_SIZE, online);
        }

        int nameX = x + AVATAR_SIZE + AVATAR_GAP;
        g.drawString(font, GuiUtil.truncate(font, peerName(s), w - (nameX - x)),
                nameX, y + (AVATAR_SIZE - font.lineHeight) / 2,
                FontPalette.title(), true);

        y += AVATAR_SIZE + 4;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        return y + 4;
    }

    private void renderMessages(GuiGraphics g, Font font, int x, int top, int w, int bottom) {
        if (blocks.isEmpty()) {
            int y = top;
            for (var line : font.split(Component.translatable("mcphone.chat.conversation_empty"), w)) {
                g.drawString(font, line, x, y, colorEmpty(), false);
                y += font.lineHeight;
            }
            maxScroll = 0;
            return;
        }

        final int viewH = bottom - top;
        maxScroll = Math.max(0, contentH - viewH);
        scrollPx = Mth.clamp(scrollPx, 0, maxScroll);

        // 不足一屏从顶往下排；超出时贴底，scrollPx 把内容往下推露出更早的消息
        int y = contentH <= viewH ? top : bottom - contentH + scrollPx;

        // enableScissor 收屏幕坐标、不跟随 pose；开机缩放动画期间到不了这里，不必补偿
        g.enableScissor(x, top, x + w, bottom);
        for (Block b : blocks) {
            if (y + b.h() > top && y < bottom) renderBlock(g, font, b, x, y, w);
            y += b.h() + BLOCK_GAP;
        }
        g.disableScissor();
    }

    private void renderBlock(GuiGraphics g, Font font, Block b, int x, int y, int w) {
        if (b.stamp()) {
            g.drawString(font, b.lines().get(0), x + (w - b.w()) / 2, y + STAMP_PAD_Y,
                    colorStamp(), false);
            return;
        }

        int bx = b.self() ? x + w - b.w() : x;
        PhoneSkin.drawOrFill(g,
                b.self() ? PhoneSkin.Element.CHAT_BUBBLE_SELF : PhoneSkin.Element.CHAT_BUBBLE_PEER,
                bx, y, b.w(), b.h(),
                b.self() ? COLOR_BUBBLE_SELF : COLOR_BUBBLE_PEER);

        int ty = y + BUBBLE_PAD_Y;
        for (var line : b.lines()) {
            g.drawString(font, line, bx + BUBBLE_PAD_X, ty,
                    b.self() ? COLOR_TEXT_SELF : COLOR_TEXT_PEER, false);
            ty += font.lineHeight;
        }
    }

    private void renderInputBar(GuiGraphics g, Font font, int x, int y, int w,
                                int mouseX, int mouseY, float partialTick) {

        String send = Component.translatable("mcphone.chat.send").getString();
        int sendW = font.width(send) + 4;
        int boxW = w - sendW - 2;

        PhoneSkin.drawOrFill(g, PhoneSkin.Element.CHAT_INPUT_BAR,
                x, y, boxW, INPUT_H, COLOR_INPUT_BG);

        // 无边框的 EditBox 不会自己垂直居中，手动摆到栏中间
        int textY = y + (INPUT_H - font.lineHeight) / 2 + 1;
        int textW = boxW - INPUT_TEXT_PAD * 2 - cursorRoom(font);

        if (box == null) {
            box = new EditBox(font, x + INPUT_TEXT_PAD, textY,
                    textW, INPUT_H - 4,
                    Component.translatable("mcphone.app.chat"));
            box.setMaxLength(ChatMessage.MAX_TEXT_LENGTH);
            box.setBordered(false);
            box.setFocused(true);
        } else {
            box.setX(x + INPUT_TEXT_PAD);
            box.setY(textY);
            box.setWidth(textW);
        }
        box.render(g, mouseX, mouseY, partialTick);

        int sendX = x + w - sendW;
        sendHovered = mouseX >= sendX && mouseX <= x + w
                   && mouseY >= y && mouseY < y + INPUT_H;

        boolean empty = box.getValue().isBlank();
        g.drawString(font, send, sendX + 2, textY,
                empty ? COLOR_SEND_OFF : (sendHovered ? COLOR_SEND_HOVER : COLOR_SEND), false);
    }

    /** 消息列表没换实例就不重排：ChatClientCache 每次收包都产出新的不可变列表，身份没变即内容没变 */
    private void relayout(Font font, int maxW) {
        List<ChatMessage> src = ChatClientCache.getMessages();
        if (src == laidOutFrom && maxW == laidOutWidth) return;

        final UUID selfId = selfId();
        final int bubbleMaxW = Math.max(24, (int) (maxW * BUBBLE_MAX_RATIO));
        final int textMaxW = bubbleMaxW - BUBBLE_PAD_X * 2;

        List<Block> out = new ArrayList<>();
        long prevTime = 0L;

        for (ChatMessage m : src) {
            if (m.time() - prevTime > STAMP_GAP_MS) {
                FormattedCharSequence stamp =
                        Component.literal(formatStamp(m.time())).getVisualOrderText();
                out.add(new Block(true, false, List.of(stamp),
                        font.width(stamp), font.lineHeight + STAMP_PAD_Y * 2));
            }
            prevTime = m.time();

            List<FormattedCharSequence> lines = font.split(Component.literal(m.text()), textMaxW);
            int textW = 0;
            for (var line : lines) textW = Math.max(textW, font.width(line));

            out.add(new Block(false, selfId != null && selfId.equals(m.sender()), lines,
                    textW + BUBBLE_PAD_X * 2,
                    lines.size() * font.lineHeight + BUBBLE_PAD_Y * 2));
        }

        int total = 0;
        for (Block b : out) total += b.h() + BLOCK_GAP;

        // 翻着历史时来了新消息，把新增高度补进滚动量，视图才不会跳；贴底时不补
        if (scrollPx > 0 && total > contentH) scrollPx += total - contentH;

        blocks = List.copyOf(out);
        contentH = total;
        laidOutFrom = src;
        laidOutWidth = maxW;
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && sendHovered) {
            send();
            return true;
        }
        if (box != null) box.mouseClicked(mx, my, button);
        return false;
    }

    /** 不管返回什么，调用方都该吃掉按键，别让 e 漏到背包键 */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {   // Enter / 小键盘 Enter
            send();
            return true;
        }
        return box != null && box.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char c, int modifiers) {
        return box != null && box.charTyped(c, modifiers);
    }

    /** 只发包、不本地插入：服务端校验没过会静默丢弃，自己那条由服务端回声送回 */
    private void send() {
        if (box == null || peer == null) return;

        String text = box.getValue();
        if (text.isBlank()) return;

        MCphoneNetwork.sendToServer(new SendChatMessagePacket(peer, text));
        box.setValue("");

        // 回到底部，自己刚发的那条得看得见
        scrollPx = 0;
    }

    public boolean mouseScrolled(double scrollY) {
        if (maxScroll <= 0) return false;

        scrollPx = Mth.clamp(scrollPx + (int) (scrollY * SCROLL_STEP), 0, maxScroll);
        return true;
    }

    /** 定时拉会话摘要：消息靠推送，但标题上的在线状态没有推送 */
    private void maybeRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < REFRESH_INTERVAL_MS) return;

        lastRequestMs = now;
        MCphoneNetwork.sendToServer(new RequestConversationsPacket());
    }

    /** 会话开着时来了新消息，补一次已读上报：服务端只在拉历史时标已读 */
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

    private static String formatStamp(long time) {
        var zone = ZoneId.systemDefault();
        var dateTime = Instant.ofEpochMilli(time).atZone(zone);
        return dateTime.toLocalDate().equals(LocalDate.now(zone))
                ? dateTime.format(TIME_FORMAT)
                : dateTime.format(DATE_TIME_FORMAT);
    }

}
