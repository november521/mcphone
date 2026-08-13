package com.november.mcphone.gui;

import com.november.mcphone.chat.ChatMessage;
import com.november.mcphone.network.chat.ChatClientCache;
import com.november.mcphone.network.chat.ConversationSummary;
import com.november.mcphone.network.chat.RequestConversationsPacket;
import com.november.mcphone.network.chat.RequestMessagesPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 单个会话界面 —— 和某一位好友的聊天记录。
 *
 * 数据全部来自 {@link ChatClientCache}，本类不持有真值。历史消息在进入
 * 时拉一次，之后靠服务端推送追加，不轮询。
 *
 * ============================================================
 * 为什么要自己排版而不是一条一行
 * ============================================================
 *
 * 手机屏幕只有 120 像素宽，一条 256 字的消息不折行的话，绝大部分内容
 * 会被截断成省略号。所以每条消息先按气泡宽度 split 成多行，再整块画。
 *
 * 排版结果缓存起来，只在消息列表换了实例时重算：{@link ChatClientCache}
 * 每次收包都产出新的不可变列表，用身份比较就能判断变没变，不必逐条比对。
 * 不缓存的话，100 条消息每帧都要 split 一遍。
 *
 * ============================================================
 * 滚动以像素计，不以条数计
 * ============================================================
 *
 * 每条消息高度不等（一行到二十行都可能），按条滚动的话，最后一条如果
 * 很长就永远看不到它的结尾——而那恰恰是最该看到的部分。
 */
public final class ChatConversation {

    /** 左右内边距 */
    private static final int PAD = 4;

    /** 刷新间隔，与会话列表同步 */
    private static final long REFRESH_INTERVAL_MS = 3000L;

    /** 气泡最多占屏幕宽度的比例。留出对侧空白，一眼能看出谁说的 */
    private static final float BUBBLE_MAX_RATIO = 0.72f;

    private static final int BUBBLE_PAD_X = 3;
    private static final int BUBBLE_PAD_Y = 2;

    /** 相邻两块之间的竖直间距 */
    private static final int BLOCK_GAP = 3;

    /** 时间戳行上下各留的空隙，让它与前后气泡分开 */
    private static final int STAMP_PAD_Y = 2;

    /** 相邻两条消息间隔超过这么久，中间才插一行时间 */
    private static final long STAMP_GAP_MS = 5 * 60 * 1000L;

    /** 滚轮一格滚多少像素 */
    private static final int SCROLL_STEP = 18;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    // ---- 颜色（贴图缺失时的兜底） ----
    private static final int COLOR_ONLINE = 0xFF55DD55;
    private static final int COLOR_OFFLINE = 0xFF777777;
    private static final int COLOR_BUBBLE_SELF = 0xFF2E6FDB;
    private static final int COLOR_BUBBLE_PEER = 0xFF3A3A4E;
    private static final int COLOR_TEXT_SELF = 0xFFFFFFFF;
    private static final int COLOR_TEXT_PEER = 0xFFEEEEEE;
    private static final int COLOR_STAMP = 0xFF777777;
    private static final int COLOR_EMPTY = 0xFF888888;

    /** 当前会话的对端；null 表示不在会话界面 */
    private UUID peer;

    private long lastRequestMs;

    /** 从最新一条往回翻了多少像素。0 ＝ 贴着最新一条 */
    private int scrollPx;

    /** 上一帧算出的可翻动上限，滚轮据此夹紧 */
    private int maxScroll;

    // ---- 排版缓存 ----
    private List<Block> blocks = List.of();
    private int contentH;
    private List<ChatMessage> laidOutFrom;
    private int laidOutWidth = -1;

    /**
     * 排版后的一块。
     *
     * @param stamp true 表示这是一行居中的时间戳，不画气泡
     * @param self  是不是自己发的 —— 决定左右对齐与气泡配色
     * @param lines 折行后的文字
     * @param w     块宽（气泡含内边距；时间戳即文字宽）
     * @param h     块高
     */
    private record Block(boolean stamp, boolean self, List<FormattedCharSequence> lines,
                         int w, int h) {}

    // ============================================================
    //  生命周期
    // ============================================================

    /**
     * 进入某个会话。
     *
     * 先把对端记进缓存，再发请求——顺序不能颠倒：新消息推送可能抢在历史
     * 消息之前到达，那时缓存若还不知道打开的是谁，这条消息会被直接丢掉。
     *
     * 服务端收到这个请求会顺带把会话标为已读，未读红点随之清零。
     */
    public void open(UUID peer) {
        this.peer = peer;
        this.scrollPx = 0;
        this.maxScroll = 0;
        // 不置零：会话列表那边刚拉过摘要，标题的名字与在线状态现成可用，
        // 一进来就再拉一次纯属重复。等下一轮定时刷新即可
        this.lastRequestMs = System.currentTimeMillis();

        // 强制重排：上一个会话的排版结果还在，不清的话会闪出别人的聊天记录
        this.laidOutFrom = null;
        this.blocks = List.of();
        this.contentH = 0;

        ChatClientCache.openConversation(peer);
        PacketDistributor.sendToServer(new RequestMessagesPacket(peer));
    }

    /** 离开会话：缓存里的消息一并释放，下次进来重新拉 */
    public void close() {
        peer = null;
        laidOutFrom = null;
        blocks = List.of();
        contentH = 0;
        ChatClientCache.closeConversation();
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

        y = renderHeader(g, font, x, y, w);

        relayout(font, w);
        renderMessages(g, font, x, y, w, bottom);
    }

    /** 标题行：在线圆点 + 对方名字 */
    private int renderHeader(GuiGraphics g, Font font, int x, int y, int w) {
        ConversationSummary s = summary();

        int dotSize = 3;
        int dotY = y + (font.lineHeight - dotSize) / 2 + 1;
        g.fill(x, dotY, x + dotSize, dotY + dotSize,
                s != null && s.online() ? COLOR_ONLINE : COLOR_OFFLINE);

        int nameX = x + dotSize + 3;
        g.drawString(font, truncate(font, peerName(s), w - (nameX - x)),
                nameX, y, PhoneTheme.FONT_COLOR_TITLE, true);

        y += font.lineHeight + 4;
        g.fill(x, y, x + w, y + 1, 0x44FFFFFF);
        return y + 4;
    }

    private void renderMessages(GuiGraphics g, Font font, int x, int top, int w, int bottom) {
        if (blocks.isEmpty()) {
            int y = top;
            for (var line : font.split(Component.translatable("mcphone.chat.conversation_empty"), w)) {
                g.drawString(font, line, x, y, COLOR_EMPTY, false);
                y += font.lineHeight;
            }
            maxScroll = 0;
            return;
        }

        final int viewH = bottom - top;
        maxScroll = Math.max(0, contentH - viewH);
        scrollPx = Math.clamp(scrollPx, 0, maxScroll);

        // 内容不足一屏就从顶往下排；超出时贴着底部显示最新，
        // scrollPx 把整块内容往下推，推多少就露出多少更早的消息
        int y = contentH <= viewH ? top : bottom - contentH + scrollPx;

        // 裁掉溢出到标题栏与导航栏的部分。
        // 原版 enableScissor 收的是屏幕坐标、不跟随 pose 变换，这里不必为
        // 开机缩放动画补偿：那 150 毫秒里必定还停在主屏，到不了会话界面。
        g.enableScissor(x, top, x + w, bottom);
        for (Block b : blocks) {
            // 完全在视口外的块直接跳过，省掉一次 drawString
            if (y + b.h() > top && y < bottom) renderBlock(g, font, b, x, y, w);
            y += b.h() + BLOCK_GAP;
        }
        g.disableScissor();
    }

    private void renderBlock(GuiGraphics g, Font font, Block b, int x, int y, int w) {
        if (b.stamp()) {
            g.drawString(font, b.lines().get(0), x + (w - b.w()) / 2, y + STAMP_PAD_Y,
                    COLOR_STAMP, false);
            return;
        }

        // 自己的靠右、对方的靠左——不看头像也知道谁在说话
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

    // ============================================================
    //  排版
    // ============================================================

    /**
     * 把消息列表排成一块块可画的内容。
     *
     * 消息列表没换实例就直接返回：{@link ChatClientCache} 每次收包都产出
     * 一份新的不可变列表，身份没变就说明内容一个字都没变。
     */
    private void relayout(Font font, int maxW) {
        List<ChatMessage> src = ChatClientCache.getMessages();
        if (src == laidOutFrom && maxW == laidOutWidth) return;

        final UUID selfId = selfId();
        final int bubbleMaxW = Math.max(24, (int) (maxW * BUBBLE_MAX_RATIO));
        final int textMaxW = bubbleMaxW - BUBBLE_PAD_X * 2;

        List<Block> out = new ArrayList<>();
        long prevTime = 0L;

        for (ChatMessage m : src) {
            // 隔了很久才说的下一句，中间插一行时间；连着说的不插，
            // 否则每条都顶一个时间戳，屏幕一半都是灰字
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

            // 气泡宽度取最宽的一行，每行都画进同一个矩形里，
            // 逐行贴合的话右边缘会参差不齐
            out.add(new Block(false, selfId != null && selfId.equals(m.sender()), lines,
                    textW + BUBBLE_PAD_X * 2,
                    lines.size() * font.lineHeight + BUBBLE_PAD_Y * 2));
        }

        int total = 0;
        for (Block b : out) total += b.h() + BLOCK_GAP;

        // 正翻着历史时来了新消息：内容变高会把视图整体顶上去，
        // 把新增的高度补进滚动量，眼下看的这几条才不会跳走。
        // 贴底时（scrollPx 为 0）不补，新消息就该自然顶上来。
        if (scrollPx > 0 && total > contentH) scrollPx += total - contentH;

        blocks = List.copyOf(out);
        contentH = total;
        laidOutFrom = src;
        laidOutWidth = maxW;
    }

    // ============================================================
    //  鼠标
    // ============================================================

    public boolean mouseScrolled(double scrollY) {
        if (maxScroll <= 0) return false;

        // 向上滚 ＝ 想看更早的 ＝ 把内容往下推
        scrollPx = Math.clamp(scrollPx + (int) (scrollY * SCROLL_STEP), 0, maxScroll);
        return true;
    }

    // ============================================================
    //  内部
    // ============================================================

    /**
     * 定时拉一次会话摘要。
     *
     * 消息本身不必轮询——服务端会主动推送。这里要的是标题上的在线圆点：
     * 它没有推送，不刷新的话对方早下线了这边还亮着绿点。
     *
     * 顺带让退出会话时的列表已经是新的，不必等列表自己那一轮刷新。
     */
    private void maybeRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < REFRESH_INTERVAL_MS) return;

        lastRequestMs = now;
        PacketDistributor.sendToServer(new RequestConversationsPacket());
    }

    /** 从会话列表快照里找当前对端那一行，找不到返回 null */
    private ConversationSummary summary() {
        if (peer == null) return null;
        for (ConversationSummary c : ChatClientCache.getConversations()) {
            if (c.id().equals(peer)) return c;
        }
        return null;
    }

    /** 摘要里有名字就用它；还没拉到时退回 UUID 前 8 位，总比一片空白强 */
    private String peerName(ConversationSummary s) {
        if (s != null) return s.name();
        return peer == null ? "" : peer.toString().substring(0, 8);
    }

    private static UUID selfId() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : player.getUUID();
    }

    /** 今天的只显示时刻，更早的带上日期——和会话列表同一个取舍 */
    private static String formatStamp(long time) {
        var zone = ZoneId.systemDefault();
        var dateTime = Instant.ofEpochMilli(time).atZone(zone);
        return dateTime.toLocalDate().equals(LocalDate.now(zone))
                ? dateTime.format(TIME_FORMAT)
                : dateTime.format(DATE_TIME_FORMAT);
    }

    private static String truncate(Font font, String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
    }
}
