package com.november.mcphone.core.client;

import com.november.mcphone.feature.chat.ChatMessage;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;

import java.util.UUID;

/**
 * 收到消息时右上角弹出的通知。
 *
 * ============================================================
 * 为什么用通知而不是往聊天框里发一句
 * ============================================================
 *
 * 聊天框是公共场所：玩家可能装了聊天管理或过滤类模组，也可能正被别的
 * 模组刷屏，消息提醒混进去容易被淹没，还会永久占着聊天记录。
 *
 * 通知是原版专为这件事准备的机制——有独立队列、自动排队、自动消失，
 * 连"叮"的一声都是原版 {@link Toast.Visibility#SHOW} 自带的，
 * 不必自己播音效。
 *
 * ============================================================
 * 同一个人连发要合并
 * ============================================================
 *
 * 原版通知区只有 5 个槽位，满了就排队。一个人连发五条就能把别的通知
 * 全挤掉，所以同一个发信人只占一条：后来的消息更新这一条的正文与计数，
 * 并把停留时间重新计起。
 *
 * 合并靠 {@link #getToken()} 返回发信人 UUID，
 * {@link ToastComponent#getToast} 据此找到已在显示或还在排队的那一条。
 */
public final class PhoneToast implements Toast {

    /** 与原版槽位一致，照这个尺寸画才不会和别的模组的通知错位 */
    private static final int WIDTH = 160;
    private static final int HEIGHT = 32;

    /** 头像边长。24 ＝ 皮肤头部 8×8 的三倍，整数倍放大才不糊 */
    private static final int AVATAR_SIZE = 24;

    private static final int PAD = 4;

    /** 停留时长。比原版通知略长一点：消息比成就更值得看清楚 */
    private static final long DISPLAY_TIME_MS = 5000L;

    /** 预览最多留几个字，够填满通知那一行就行 */
    private static final int PREVIEW_MAX_CHARS = 64;

    // ---- 颜色（贴图缺失时的兜底） ----
    private static final int COLOR_BG = 0xFF1A1A2E;
    private static final int COLOR_BORDER = 0xFF0F3460;
    private static final int COLOR_NAME = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFFBBBBBB;
    private static final int COLOR_BADGE_BG = 0xFFDD3333;

    private final UUID sender;
    private final String senderName;

    private String text;

    /** 合并进来的条数，1 表示只有一条，不显示角标 */
    private int count = 1;

    /** 最后一次更新的时刻，用来把停留时间重新计起 */
    private long lastUpdateMs = -1L;

    private boolean changed = true;

    public PhoneToast(UUID sender, String senderName, String text) {
        this.sender = sender;
        this.senderName = senderName;
        this.text = text;
    }

    /**
     * 同一个人又来消息了：换成最新一条，条数加一，停留时间重新计起。
     *
     * 显示最新一条而不是最早那条：玩家瞥一眼通知想知道的是"他刚说了
     * 什么"，不是三十秒前说过什么。
     */
    public void addMessage(String newText) {
        this.text = newText;
        this.count++;
        this.changed = true;
    }

    /** 发信人 UUID —— 原版据此找到同一个人的通知来合并 */
    @Override
    public Object getToken() {
        return sender;
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        return HEIGHT;
    }

    @Override
    public Visibility render(GuiGraphics g, ToastComponent component, long timeSinceLastVisible) {
        // 有新消息并进来就把计时重置，否则第五条刚到通知就消失了
        if (changed) {
            lastUpdateMs = timeSinceLastVisible;
            changed = false;
        }

        Font font = component.getMinecraft().font;

        // 底：贴图优先，没有贴图就画纯色加一圈边
        if (!PhoneSkin.draw(g, PhoneSkin.Element.TOAST_BG, 0, 0, WIDTH, HEIGHT)) {
            g.fill(0, 0, WIDTH, HEIGHT, COLOR_BG);
            g.renderOutline(0, 0, WIDTH, HEIGHT, COLOR_BORDER);
        }

        PlayerAvatar.draw(g, sender, PAD, (HEIGHT - AVATAR_SIZE) / 2, AVATAR_SIZE);

        int textX = PAD + AVATAR_SIZE + PAD;
        int textW = WIDTH - textX - PAD;

        // 有多条时右上角留出角标的位置，名字不能压到它
        int badgeW = count > 1 ? font.width(countLabel()) + 4 : 0;
        g.drawString(font, truncate(font, senderName, textW - badgeW - 2),
                textX, 7, COLOR_NAME, false);
        g.drawString(font, truncate(font, text, textW), textX, 18, COLOR_TEXT, false);

        if (count > 1) {
            int badgeX = WIDTH - PAD - badgeW;
            // 与会话列表的未读角标共用贴图，换肤时两处一致
            PhoneSkin.drawOrFill(g, PhoneSkin.Element.UNREAD_BADGE,
                    badgeX, 6, badgeW, font.lineHeight + 1, COLOR_BADGE_BG);
            g.drawString(font, countLabel(), badgeX + 2, 7, COLOR_NAME, false);
        }

        // 原版按显示时长的倍率缩放，玩家在设置里调过通知时间就该跟着变
        double limit = DISPLAY_TIME_MS * component.getNotificationDisplayTimeMultiplier();
        return timeSinceLastVisible - lastUpdateMs < limit ? Visibility.SHOW : Visibility.HIDE;
    }

    /** 超过 99 就显示 99+，否则一个三位数会把角标撑变形 */
    private String countLabel() {
        return count > 99 ? "99+" : String.valueOf(count);
    }

    private static String truncate(Font font, String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    /**
     * 通知里显示的消息预览。
     *
     * 先按字符截一刀：通知只有一行的地方，一条 256 字的消息里 240 字
     * 都画不出来，何必让 truncate 逐字去量宽度。真正的按像素截断仍由
     * 渲染时的 truncate 负责，这里只是别让它做无用功。
     */
    public static String preview(ChatMessage message) {
        String raw = message.text();
        return raw.length() <= PREVIEW_MAX_CHARS ? raw : raw.substring(0, PREVIEW_MAX_CHARS);
    }

    /** 拿不到名字时的兜底显示，与会话界面同一个规矩 */
    public static String fallbackName(UUID id) {
        return id.toString().substring(0, 8);
    }
}
