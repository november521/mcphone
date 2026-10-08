package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.client.PlayerAvatar;
import com.november.mcphone.feature.chat.ChatMessage;
import com.november.mcphone.feature.chat.net.ChatClientCache;
import com.november.mcphone.feature.chat.client.ChatMessageLayout.Block;
import com.november.mcphone.feature.chat.client.ChatMessageLayout.BlockType;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.november.mcphone.feature.chat.client.ChatMessageLayout.BLOCK_GAP;
import static com.november.mcphone.feature.chat.client.ChatMessageLayout.STAMP_PAD_Y;

/** 消息区拥有排版缓存、滚动量和可见图片命中区，状态跟随一个会话页实例。 */
final class ChatMessagePane {
    private static final int AVATAR_SIZE = ChatLayout.AVATAR_SIZE;
    private static final int AVATAR_GAP = 3;
    private static final int COLOR_BUBBLE_SELF = PhoneTheme.COLOR_CHAT_BUBBLE_SELF;
    private static final int COLOR_BUBBLE_PEER = PhoneTheme.COLOR_CHAT_BUBBLE_PEER;
    private static final int COLOR_TEXT_SELF = PhoneTheme.FONT_COLOR_CHAT_SELF;
    private static final int COLOR_TEXT_PEER = PhoneTheme.FONT_COLOR_CHAT_PEER;
    private static int colorStamp() { return FontPalette.timestamp(); }
    private static int colorEmpty() { return FontPalette.subtle(); }
    private UUID peer;
    private UUID selfId;
    private UUID laidOutSelf;
    private Font laidOutFont;
    private List<ChatMessage> laidOutFrom;
    private int laidOutWidth = -1;
    private List<Block> blocks = List.of();
    private final ChatScrollState scroll = new ChatScrollState();
    private final List<ImageHit> imageHits = new ArrayList<>();
    private int messageTop, messageBottom;
    private record ImageHit(UUID image, int x, int y, int w, int h) {}

    void reset() {
        peer = selfId = laidOutSelf = null;
        laidOutFont = null;
        laidOutFrom = null;
        laidOutWidth = -1;
        blocks = List.of();
        imageHits.clear();
        messageTop = messageBottom = 0;
        scroll.reset();
    }

    void latest() { scroll.latest(); }
    boolean mouseScrolled(double amount) { return scroll.scroll(amount); }

    UUID imageAt(double mx, double my) {
        if (my < messageTop || my >= messageBottom) return null;
        for (ImageHit hit : imageHits) {
            if (GuiUtil.hit(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) return hit.image();
        }
        return null;
    }

    private void relayout(Font font, int width) {
        List<ChatMessage> src = ChatClientCache.getMessages();
        if (src == laidOutFrom && width == laidOutWidth && font == laidOutFont
                && Objects.equals(selfId, laidOutSelf)) return;
        blocks = ChatMessageLayout.build(font, src, width, selfId);
        int total = 0;
        for (Block b : blocks) total += b.rowHeight() + BLOCK_GAP;
        scroll.contentChanged(total);
        laidOutFrom = src;
        laidOutWidth = width;
        laidOutFont = font;
        laidOutSelf = selfId;
    }

    void render(GuiGraphics g, Font font, int x, int top, int w, int bottom, UUID peer, UUID selfId) {
        this.peer = peer;
        this.selfId = selfId;
        relayout(font, w);
        // 每帧重建：滚一下、来一条新消息，位置就全变了
        imageHits.clear();
        messageTop = top;
        messageBottom = bottom;

        if (blocks.isEmpty()) {
            int y = top;
            for (var line : font.split(Component.translatable("mcphone.chat.conversation_empty"),
                    ChatLayout.unscaledWidth(w, ChatLayout.TEXT_SCALE))) {
                ChatUi.text(g, font, line, x, y, ChatLayout.TEXT_SCALE, colorEmpty());
                y += ChatUi.lineHeight(font, ChatLayout.TEXT_SCALE);
            }
            scroll.viewport(0);
            return;
        }

        final int viewH = bottom - top;
        scroll.viewport(viewH);

        // 不足一屏从顶往下排；超出时贴底，滚动偏移把内容往下推露出更早的消息
        int y = scroll.contentHeight() <= viewH ? top : bottom - scroll.contentHeight() + scroll.offset();

        // 走 GuiUtil 那一层：原版的 enableScissor 收窗口坐标、不跟随 pose，而整个手机是
        // 套在一层缩放里画的（界面大小 × 开机动画）。直接交本地坐标，界面大小一改字就被切
        GuiUtil.enableScissor(g, x, top, x + w, bottom);
        for (Block b : blocks) {
            if (y + b.rowHeight() > top && y < bottom) renderBlock(g, font, b, x, y, w);
            y += b.rowHeight() + BLOCK_GAP;
        }
        GuiUtil.disableScissor(g);
    }

    private void renderBlock(GuiGraphics g, Font font, Block b, int x, int y, int w) {
        if (b.type() == BlockType.STAMP) {
            ChatUi.text(g, font, b.lines().get(0), x + (w - b.w()) / 2, y + STAMP_PAD_Y,
                    ChatLayout.META_SCALE, colorStamp());
            return;
        }

        UUID avatar = b.self() ? selfId : peer;
        int avatarY = y + b.placement().avatarY();
        int contentY = y + b.placement().contentY();
        // placement 在上方保留昵称行，未来群聊可在这里放发送者名；当前不伪造群聊功能。
        if (avatar != null) PlayerAvatar.draw(g, avatar,
                b.self() ? x + w - AVATAR_SIZE : x, avatarY, AVATAR_SIZE);
        int bx = b.self() ? x + w - AVATAR_SIZE - AVATAR_GAP - b.w()
                          : x + AVATAR_SIZE + AVATAR_GAP;

        // 图片不套气泡：真实的聊天软件里图片就是图片本身，没有底色也没有一圈留白，
        // 谁发的靠左右对齐看得出来。套一层气泡等于在图周围多画一圈没有意义的色块，
        // 而手机屏幕只有 120 宽，那一圈还要从图身上扣
        if (b.type() == BlockType.IMAGE) {
            renderImageBlock(g, font, b, bx, contentY);
            return;
        }

        PhoneSkin.drawOrFill(g,
                b.self() ? PhoneSkin.Element.CHAT_BUBBLE_SELF : PhoneSkin.Element.CHAT_BUBBLE_PEER,
                bx, contentY, b.w(), b.h(),
                b.self() ? COLOR_BUBBLE_SELF : COLOR_BUBBLE_PEER);

        var origin = b.textOrigin();
        float ty = contentY + origin.y();
        for (var line : b.lines()) {
            ChatUi.text(g, font, line, bx + origin.x(), ty, ChatLayout.TEXT_SCALE,
                    b.self() ? COLOR_TEXT_SELF : COLOR_TEXT_PEER);
            ty += ChatUi.lineHeight(font, ChatLayout.TEXT_SCALE);
        }
    }

    private void renderImageBlock(GuiGraphics g, Font font, Block b, int bx, int y) {
        int w = b.w();
        int h = b.h();

        // 每帧都告知：条目被逐出后会被重建成一条空的，而放大图那一层只有一个 id
        ChatImageCache.declare(b.image(), b.frames(), b.frameMs());

        var texture = ChatImageCache.get(b.image());
        if (texture != null) {
            // 有像素才记位置：点一张还没到、或者已经过期的图，放大了也只是一块空白
            imageHits.add(new ImageHit(b.image(), bx, y, w, h));
            ChatImageViewer.drawImage(g, texture, b.image(), bx, y, w, h);
            return;
        }

        g.fill(bx, y, bx + w, y + h, PhoneTheme.COLOR_SCRIM);

        String hint = switch (ChatImageCache.status(b.image())) {
            case GONE -> Component.translatable("mcphone.chat.image_expired").getString();
            case BROKEN -> Component.translatable("mcphone.chat.image_broken_local").getString();
            case LOADING, READY -> "…";
        };
        // 先截再居中：那一块窄的时候（一张竖图）「已过期」放不下，按截断前的宽度算会偏出去
        hint = GuiUtil.truncate(font, hint, w - 2);
        g.drawString(font, hint,
                bx + Math.max(0, (w - font.width(hint)) / 2),
                y + (h - font.lineHeight) / 2,
                colorEmpty(), false);
    }
}
