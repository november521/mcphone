package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.ServerConfig;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.client.PlayerAvatar;
import com.november.mcphone.feature.chat.ChatMessage;
import com.november.mcphone.feature.chat.ImageBody;
import com.november.mcphone.feature.chat.TextBody;
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
import net.neoforged.neoforge.network.PacketDistributor;

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

    /**
     * 图片气泡最高多少像素。
     *
     * 气泡最宽只有 80 出头，一张 16:9 的截图按宽度算出来才 45 高，这个数管的是竖构图的图：
     * 不封顶的话，一张竖着的截图会占掉大半个屏幕，前后几条消息全被挤出视野。
     */
    private static final int IMAGE_MAX_H = 56;

    /** 输入栏左边那个图片键的边长。与音乐页那几个键一样大，它们是同一家的 */
    private static final int IMAGE_BTN = 9;

    /** 图片键与输入框之间的空隙 */
    private static final int IMAGE_BTN_GAP = 2;

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

    private boolean imageBtnHovered;

    /** 玩家点了图片键，等 PhoneScreen 取走去开选照片那一页 */
    private boolean pendingPickPhoto;

    /** 正在放大看的那张图，null 表示没有 */
    private UUID viewingImage;

    /** 本帧画出来的图片气泡，供点击放大用；每帧重建，因为滚动一下位置就全变了 */
    private final List<ImageHit> imageHits = new ArrayList<>();

    /** 本帧消息区的上下边界。气泡是裁剪着画的，被裁掉的那半截不该还点得动 */
    private int messageTop;
    private int messageBottom;

    private record ImageHit(UUID image, int x, int y, int w, int h) {}

    /** 上次上报过已读的那份消息列表，用来发现"又来新消息了" */
    private List<ChatMessage> markedFrom;

    private List<Block> blocks = List.of();
    private int contentH;
    private List<ChatMessage> laidOutFrom;
    private int laidOutWidth = -1;

    /** 排版后的一块是哪一种 */
    private enum BlockType {
        /** 居中的时间戳行，不画气泡 */
        STAMP,
        /** 文字气泡 */
        TEXT,
        /** 图片气泡 */
        IMAGE
    }

    /**
     * 排版后的一块。w/h 含内边距；lines 只有文字块用得上，image 只有图片块用得上。
     *
     * 图片块为什么在这一步就把尺寸算好：像素是"看到了才去要"的（见 {@link ChatImageCache}），
     * 拿到之前也得把气泡摆出来，尺寸按消息自带的宽高算（见 ImageBody）。等图到了再按真实
     * 比例重排的话，那一下跳动恰好发生在玩家正看着的地方。
     */
    private record Block(BlockType type, boolean self, List<FormattedCharSequence> lines,
                         UUID image, int w, int h) {}

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

        this.viewingImage = null;
        this.pendingPickPhoto = false;
        this.imageHits.clear();

        ChatClientCache.openConversation(peer);
        this.markedFrom = ChatClientCache.getMessages();

        PacketDistributor.sendToServer(new RequestMessagesPacket(peer));
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
        imageBtnHovered = false;
        viewingImage = null;
        pendingPickPhoto = false;
        imageHits.clear();
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

        // 本帧画到的图片里还差像素的，攒一批去要。要在画完之后：画的时候才知道哪几张可见
        ChatImageCache.flushRequests(peer);

        // 放大图盖在所有东西上面，连输入栏一起盖住——它此刻不是能用的东西
        if (viewingImage != null) {
            renderImageViewer(g, font, phoneLeft, phoneTop + statusH,
                    screenW, screenH - statusH - navH);
        }
    }

    /**
     * 点开一张图看大的：整块内容区盖上一层黑底，图等比居中。
     *
     * 为什么值得有这一手：气泡里那张最宽只有 80 个 GUI 像素，看得出是什么，看不清写了什么。
     * 存下来的图有 256 的长边（见 ChatImage），铺满内容区正好用得上。
     *
     * 点哪儿都关掉，导航栏的返回键也关（见 {@link #dismissViewer()}）——这一层不是一页，
     * 玩家不会觉得自己"进去了"，就不该要求他找一个特定的地方点。
     */
    private void renderImageViewer(GuiGraphics g, Font font,
                                   int areaX, int areaY, int areaW, int areaH) {

        g.fill(areaX, areaY, areaX + areaW, areaY + areaH, PhoneTheme.COLOR_OVERLAY);

        var texture = ChatImageCache.get(viewingImage);
        if (texture == null) {
            String loading = Component.translatable("mcphone.chat.image_loading").getString();
            g.drawString(font, loading,
                    areaX + (areaW - font.width(loading)) / 2,
                    areaY + (areaH - font.lineHeight) / 2, colorEmpty(), false);
            return;
        }

        int hintH = font.lineHeight + 2;
        GuiUtil.drawFitted(g, texture, areaX + 2, areaY + 2, areaW - 4, areaH - 4 - hintH);

        String hint = Component.translatable("mcphone.chat.image_close_hint").getString();
        g.drawString(font, hint, areaX + (areaW - font.width(hint)) / 2,
                areaY + areaH - hintH, colorStamp(), false);
    }

    /** 关掉放大图。返回 false 表示本来就没在放大——那时返回键该照常退出会话 */
    public boolean dismissViewer() {
        if (viewingImage == null) return false;
        viewingImage = null;
        return true;
    }

    /** 取走"要去选一张照片"的请求，没有则返回 false */
    public boolean consumePickPhotoRequest() {
        boolean out = pendingPickPhoto;
        pendingPickPhoto = false;
        return out;
    }

    /** 当前会话的对端，PhoneScreen 发图时要知道发给谁 */
    public UUID peer() {
        return peer;
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
        // 每帧重建：滚一下、来一条新消息，位置就全变了
        imageHits.clear();
        messageTop = top;
        messageBottom = bottom;

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
        scrollPx = Math.clamp(scrollPx, 0, maxScroll);

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
        if (b.type() == BlockType.STAMP) {
            g.drawString(font, b.lines().get(0), x + (w - b.w()) / 2, y + STAMP_PAD_Y,
                    colorStamp(), false);
            return;
        }

        int bx = b.self() ? x + w - b.w() : x;
        PhoneSkin.drawOrFill(g,
                b.self() ? PhoneSkin.Element.CHAT_BUBBLE_SELF : PhoneSkin.Element.CHAT_BUBBLE_PEER,
                bx, y, b.w(), b.h(),
                b.self() ? COLOR_BUBBLE_SELF : COLOR_BUBBLE_PEER);

        if (b.type() == BlockType.IMAGE) {
            renderImageBlock(g, font, b, bx, y);
            return;
        }

        int ty = y + BUBBLE_PAD_Y;
        for (var line : b.lines()) {
            g.drawString(font, line, bx + BUBBLE_PAD_X, ty,
                    b.self() ? COLOR_TEXT_SELF : COLOR_TEXT_PEER, false);
            ty += font.lineHeight;
        }
    }

    /**
     * 图片气泡里画什么，取决于像素到了没有。
     *
     * 问一句 {@link ChatImageCache#get} 就等于告诉缓存"这一帧它是可见的"，该去要的它自己会去要，
     * 因此这里不必判断"要过没有"。三种没有像素的情况各说各的：还在路上、服务端说没了、
     * 收到了但解不开——玩家至少知道该不该等。
     */
    private void renderImageBlock(GuiGraphics g, Font font, Block b, int bx, int y) {
        int ix = bx + BUBBLE_PAD_X;
        int iy = y + BUBBLE_PAD_Y;
        int iw = b.w() - BUBBLE_PAD_X * 2;
        int ih = b.h() - BUBBLE_PAD_Y * 2;

        var texture = ChatImageCache.get(b.image());
        if (texture != null) {
            // 有像素才记位置：点一张还没到、或者已经过期的图，放大了也只是一块空白
            imageHits.add(new ImageHit(b.image(), ix, iy, iw, ih));
            GuiUtil.drawFitted(g, texture, ix, iy, iw, ih);
            return;
        }

        // 没有像素时先铺一块底，否则气泡里是一个空洞，看不出这儿本该有张图
        g.fill(ix, iy, ix + iw, iy + ih, PhoneTheme.COLOR_SCRIM);

        String hint = switch (ChatImageCache.status(b.image())) {
            case GONE -> Component.translatable("mcphone.chat.image_expired").getString();
            case BROKEN -> Component.translatable("mcphone.chat.image_broken_local").getString();
            case LOADING, READY -> "…";
        };
        // 先截再居中：气泡窄的时候（一张竖图）「已过期」放不下，按截断前的宽度算会偏出去
        hint = GuiUtil.truncate(font, hint, iw - 2);
        g.drawString(font, hint,
                ix + Math.max(0, (iw - font.width(hint)) / 2),
                iy + (ih - font.lineHeight) / 2,
                colorEmpty(), false);
    }

    private void renderInputBar(GuiGraphics g, Font font, int x, int y, int w,
                                int mouseX, int mouseY, float partialTick) {

        String send = Component.translatable("mcphone.chat.send").getString();
        int sendW = font.width(send) + 4;

        // 服主关掉发图片时连位置都不留：那一格空着比一个点了没反应的键好
        boolean canSendImage = ServerConfig.allowChatImages();
        int btnRoom = canSendImage ? IMAGE_BTN + IMAGE_BTN_GAP : 0;

        int barX = x + btnRoom;
        int boxW = w - sendW - 2 - btnRoom;

        if (canSendImage) {
            renderImageButton(g, font, x, y, mouseX, mouseY);
        } else {
            imageBtnHovered = false;
        }

        PhoneSkin.drawOrFill(g, PhoneSkin.Element.CHAT_INPUT_BAR,
                barX, y, boxW, INPUT_H, COLOR_INPUT_BG);

        // 无边框的 EditBox 不会自己垂直居中，手动摆到栏中间
        int textY = y + (INPUT_H - font.lineHeight) / 2 + 1;
        int textW = boxW - INPUT_TEXT_PAD * 2 - cursorRoom(font);

        if (box == null) {
            box = new EditBox(font, barX + INPUT_TEXT_PAD, textY,
                    textW, INPUT_H - 4,
                    Component.translatable("mcphone.app.chat"));
            box.setMaxLength(TextBody.MAX_LENGTH);
            box.setBordered(false);
            box.setFocused(true);
        } else {
            box.setX(barX + INPUT_TEXT_PAD);
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

    /**
     * 输入栏左边那个图片键：贴图优先，没有贴图就画一个 ▣ 兜底，与音乐页那几个键同一个规矩。
     *
     * 正在发一张时画成灰的并且点不动：压缩与上传是异步的，连点两下会有两次上传交错着
     * 发上去，而服务端按"片号必须连续"收（见 ChatImageUploads），交错的结果是两张都发不成。
     */
    private void renderImageButton(GuiGraphics g, Font font, int x, int barY,
                                   int mouseX, int mouseY) {

        int by = barY + (INPUT_H - IMAGE_BTN) / 2;
        boolean sending = ChatImageSender.isBusy();

        imageBtnHovered = !sending && GuiUtil.hit(mouseX, mouseY,
                x - 1, by - 1, IMAGE_BTN + 2, IMAGE_BTN + 2);

        if (imageBtnHovered) {
            g.fill(x - 1, by - 1, x + IMAGE_BTN + 1, by + IMAGE_BTN + 1,
                    PhoneTheme.COLOR_HOVER_STRONG);
        }

        if (PhoneSkin.draw(g, PhoneSkin.Element.CHAT_IMAGE, x, by, IMAGE_BTN, IMAGE_BTN)) return;

        String glyph = "▣";
        g.drawString(font, glyph, x + (IMAGE_BTN - font.width(glyph)) / 2, by,
                sending ? COLOR_SEND_OFF : (imageBtnHovered ? COLOR_SEND_HOVER : COLOR_SEND),
                false);
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
                out.add(new Block(BlockType.STAMP, false, List.of(stamp), null,
                        font.width(stamp), font.lineHeight + STAMP_PAD_Y * 2));
            }
            prevTime = m.time();

            boolean self = selfId != null && selfId.equals(m.sender());

            if (m.body() instanceof ImageBody image) {
                out.add(imageBlock(image, self, bubbleMaxW));
                continue;
            }

            String text = m.body() instanceof TextBody t ? t.text() : m.body().preview().getString();
            List<FormattedCharSequence> lines = font.split(Component.literal(text), textMaxW);
            int textW = 0;
            for (var line : lines) textW = Math.max(textW, font.width(line));

            out.add(new Block(BlockType.TEXT, self, lines, null,
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

    /** 按消息自带的宽高等比算出图片气泡多大，宽不超过气泡上限、高不超过 {@link #IMAGE_MAX_H} */
    private static Block imageBlock(ImageBody image, boolean self, int bubbleMaxW) {
        int maxW = Math.max(8, bubbleMaxW - BUBBLE_PAD_X * 2);

        float scale = Math.min((float) maxW / image.width(), (float) IMAGE_MAX_H / image.height());
        // 比屏幕还小的图不放大：放大只会糊，而手机上的图本来就该小
        scale = Math.min(scale, 1f);

        int w = Math.max(1, Math.round(image.width() * scale));
        int h = Math.max(1, Math.round(image.height() * scale));

        return new Block(BlockType.IMAGE, self, List.of(), image.image(),
                w + BUBBLE_PAD_X * 2, h + BUBBLE_PAD_Y * 2);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        // 放大图开着时点哪儿都是关掉它，别让点击漏到下面的气泡与输入栏
        if (viewingImage != null) {
            viewingImage = null;
            return true;
        }

        if (button == 0 && imageBtnHovered) {
            pendingPickPhoto = true;
            return true;
        }
        if (button == 0 && sendHovered) {
            send();
            return true;
        }
        // 点在消息区里才算数：气泡是裁剪着画的，露在外面的那半截看不见，也就不该点得动
        if (button == 0 && my >= messageTop && my < messageBottom) {
            for (ImageHit hit : imageHits) {
                if (GuiUtil.hit(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                    viewingImage = hit.image();
                    return true;
                }
            }
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

        PacketDistributor.sendToServer(new SendChatMessagePacket(peer, text));
        box.setValue("");

        // 回到底部，自己刚发的那条得看得见
        scrollPx = 0;
    }

    public boolean mouseScrolled(double scrollY) {
        // 放大图盖着整块内容区，此时滚轮不该去翻它下面那些看不见的消息
        if (viewingImage != null) return true;

        if (maxScroll <= 0) return false;

        scrollPx = Math.clamp(scrollPx + (int) (scrollY * SCROLL_STEP), 0, maxScroll);
        return true;
    }

    /** 定时拉会话摘要：消息靠推送，但标题上的在线状态没有推送 */
    private void maybeRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < REFRESH_INTERVAL_MS) return;

        lastRequestMs = now;
        PacketDistributor.sendToServer(new RequestConversationsPacket());
    }

    /** 会话开着时来了新消息，补一次已读上报：服务端只在拉历史时标已读 */
    private void maybeMarkRead() {
        List<ChatMessage> src = ChatClientCache.getMessages();
        if (src == markedFrom || peer == null) return;

        markedFrom = src;
        PacketDistributor.sendToServer(new MarkReadPacket(peer));
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
