package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.client.PlayerAvatar;
import com.november.mcphone.feature.chat.net.ChatClientCache;
import com.november.mcphone.feature.chat.net.ConversationSummary;
import com.november.mcphone.feature.chat.net.RequestConversationsPacket;
import com.november.mcphone.feature.chat.net.TeleportToFriendPacket;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * 聊天 App 的会话列表。
 *
 * 数据全部来自 {@link ChatClientCache}，本类不持有真值——服务端才是权威，
 * 这里只负责把那份快照画出来。
 *
 * ============================================================
 * 为什么要定时刷新
 * ============================================================
 *
 * 新消息是服务端主动推的，不需要轮询。但【在线状态】和【未读数】不是：
 * 联系人上线下线不会通知我们，未读数也只有服务端算得出。所以列表打开
 * 期间每隔几秒拉一次摘要。
 *
 * 只在列表可见时才拉——关掉 App 就停，不会在后台空转。
 *
 * ============================================================
 * 一行里有两个点击区
 * ============================================================
 *
 * 点这一行的任何地方＝进会话；点名字后面那个「传送」＝传到对方身边。
 * 后者的点击区整个落在前者里面，所以判定顺序反不得：先问传送，再问行。
 * 反过来的话，点传送会连带把会话也打开。
 */
public final class ChatList {

    /** 左右内边距 */
    private static final int PAD = 4;

    /** 刷新间隔。够短，上线下线看得出来；够长，不至于每帧发包 */
    private static final long REFRESH_INTERVAL_MS = 3000L;

    /** 头像边长。16 ＝ 皮肤头部 8×8 的整数倍放大，边缘才锐利 */
    private static final int AVATAR_SIZE = 16;

    /** 头像与右侧文字之间的空隙 */
    private static final int AVATAR_GAP = 3;

    /** 传送按钮与右侧未读数／时间之间的空隙 */
    private static final int TP_GAP = 3;

    /**
     * 名字至少要占这么宽，不然这一行就没法分辨是谁了。
     *
     * 36px 大约是 6 个拉丁字符或 3 个汉字——短是短，好歹还能认人。
     * 详见下面 renderRow 里为它让路的那段。
     */
    private static final int MIN_NAME_W = 36;

    // ---- 颜色 ----
    private static int colorName() { return FontPalette.title(); }
    private static int colorPreview() { return FontPalette.preview(); }
    private static int colorTime() { return FontPalette.timestamp(); }
    private static final int COLOR_UNREAD_BG = PhoneTheme.COLOR_UNREAD_BADGE;
    private static final int COLOR_ROW_HOVER = PhoneTheme.COLOR_ROW_HOVER;

    private long lastRequestMs;
    private int scrollOffset;
    private int hoveredIdx = -1;
    private boolean addContactHovered;

    /** 待消费的"打开某个会话"请求 */
    private UUID pendingOpen;

    /** 待消费的"打开加联系人界面"请求 */
    private boolean pendingAddContact;

    /** 鼠标停在哪一行的传送按钮上，-1 表示没有 */
    private int teleportHoveredIdx = -1;

    /** 待消费的"把手机关掉"请求 */
    private boolean pendingClose;

    // ============================================================
    //  生命周期
    // ============================================================

    /** 进入会话列表：立刻拉一次，不必等定时刷新 */
    public void open() {
        scrollOffset = 0;
        hoveredIdx = -1;
        lastRequestMs = 0L;      // 置零＝下一帧立即请求
        pendingOpen = null;
        pendingAddContact = false;
        teleportHoveredIdx = -1;
        pendingClose = false;
    }

    /** 离开会话列表：停止定时刷新 */
    public void close() {
        hoveredIdx = -1;
        addContactHovered = false;
        teleportHoveredIdx = -1;
    }

    /** 取走"打开某个会话"的请求，没有则返回 null */
    public UUID consumeOpenRequest() {
        UUID out = pendingOpen;
        pendingOpen = null;
        return out;
    }

    /** 取走"打开加联系人界面"的请求 */
    public boolean consumeAddContactRequest() {
        boolean out = pendingAddContact;
        pendingAddContact = false;
        return out;
    }

    /**
     * 取走"把手机关掉"的请求。
     *
     * 点了传送就该关机：人已经在几百格外了，还举着手机的话看不见自己落在
     * 哪、周围有什么。关机只有 PhoneScreen 做得了，本类只提请求——
     * 与"打开某个会话"同一个道理，组件不该知道外面的导航结构。
     */
    public boolean consumeCloseRequest() {
        boolean out = pendingClose;
        pendingClose = false;
        return out;
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

        y = renderHeader(g, font, x, y, w, mouseX, mouseY);

        List<ConversationSummary> list = ChatClientCache.getConversations();
        if (list.isEmpty()) {
            renderEmpty(g, font, x, y, w);
            hoveredIdx = -1;
            teleportHoveredIdx = -1;
            return;
        }

        clampScroll(list.size(), bottom - y, font);
        renderRows(g, font, list, x, y, w, bottom, mouseX, mouseY);
    }

    /** 标题行：左侧标题，右侧"＋"按钮 */
    private int renderHeader(GuiGraphics g, Font font, int x, int y, int w,
                             int mouseX, int mouseY) {
        g.drawString(font, Component.translatable("mcphone.app.chat").getString(),
                x, y, FontPalette.title(), true);

        String plus = "+";
        int plusW = font.width(plus);
        int plusX = x + w - plusW - 2;
        addContactHovered = mouseX >= plusX - 3 && mouseX <= plusX + plusW + 3
                         && mouseY >= y - 2 && mouseY <= y + font.lineHeight + 2;
        g.drawString(font, plus, plusX, y,
                addContactHovered ? FontPalette.title() : FontPalette.link(), true);

        y += font.lineHeight + 4;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        return y + 4;
    }

    private void renderEmpty(GuiGraphics g, Font font, int x, int y, int w) {
        g.drawString(font, Component.translatable("mcphone.chat.empty").getString(),
                x, y, FontPalette.subtle(), false);
        y += font.lineHeight + 2;

        // 提示文字比屏幕宽时按词换行，硬截断会把话说一半
        for (var line : font.split(Component.translatable("mcphone.chat.empty_hint"), w)) {
            g.drawString(font, line, x, y, FontPalette.dim(), false);
            y += font.lineHeight;
        }
    }

    private void renderRows(GuiGraphics g, Font font, List<ConversationSummary> list,
                            int x, int y, int w, int bottom, int mouseX, int mouseY) {
        final int rowH = rowHeight(font);
        hoveredIdx = -1;
        teleportHoveredIdx = -1;

        for (int i = scrollOffset; i < list.size(); i++) {
            if (y + rowH > bottom) break;

            ConversationSummary c = list.get(i);
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + rowH;
            if (hovered) {
                hoveredIdx = i;
                g.fill(x, y, x + w, y + rowH, COLOR_ROW_HOVER);
            }

            // 悬停判定与绘制在同一处完成：按钮画在哪儿，点击区就在哪儿，
            // 两处各算一遍迟早会算出不一样的结果
            if (renderRow(g, font, c, x, y, w, mouseX, mouseY)) teleportHoveredIdx = i;
            y += rowH;
        }
    }

    /** @return 鼠标是否停在这一行的传送按钮上 */
    private boolean renderRow(GuiGraphics g, Font font, ConversationSummary c,
                              int x, int y, int w, int mouseX, int mouseY) {
        // ---- 头像：竖跨两行文字，在行内居中 ----
        // 在线状态点挂在头像右下角，比单独占一列省地方，也更像真手机
        int avatarY = y + (rowHeight(font) - AVATAR_SIZE) / 2;
        PlayerAvatar.drawWithStatus(g, c.id(), x, avatarY, AVATAR_SIZE, c.online());

        // 右侧先算宽度，名字才知道能占多少
        String right = c.unread() > 0 ? unreadLabel(c.unread()) : GuiUtil.formatTime(c.lastTime());
        int rightW = right.isEmpty() ? 0 : font.width(right) + (c.unread() > 0 ? 4 : 0);

        // 传送按钮只画给在线的人：离线传不过去，画一个点了没反应的按钮
        // 比不画更让人困惑。名字要先减掉它占的宽度，否则长名字会盖上去
        String tp = c.online()
                ? Component.translatable("mcphone.chat.teleport_action").getString()
                : "";
        int tpTextW = tp.isEmpty() ? 0 : font.width(tp);
        int tpW = tp.isEmpty() ? 0 : tpTextW + TP_GAP;

        int nameX = x + AVATAR_SIZE + AVATAR_GAP;
        int nameMaxW = w - (nameX - x) - rightW - tpW - 4;

        // 挤不下时先牺牲时间戳，不牺牲名字。
        //
        // 屏幕宽度是死的 120px，而「传送」两个汉字比英文的 TP 宽出一截——
        // 到底宽多少取决于原版字体的中日韩字形步进，那不是我们能定的数。
        // 所以这里不去赌那个数字，只保证名字有个下限：真挤到底了，就把
        // 时间戳去掉。三样东西里它最不重要——列表本来按时间排序，第二行
        // 还有内容预览，而名字是唯一能分辨"这是谁"的东西。
        //
        // 未读数不让路：那是玩家打开这个 App 真正在找的东西。
        if (nameMaxW < MIN_NAME_W && c.unread() == 0 && !right.isEmpty()) {
            right = "";
            rightW = 0;
            nameMaxW = w - (nameX - x) - tpW - 4;
        }

        String name = GuiUtil.truncate(font, c.name(), nameMaxW);
        g.drawString(font, name, nameX, y, colorName(), false);

        boolean tpHovered = false;
        if (!tp.isEmpty()) {
            int tpX = x + w - rightW - tpW;
            // 点击区四边各放宽 3px，与标题栏那个"+"同一套做法：屏幕只有
            // 120px 宽，按字宽严丝合缝地判定的话很难点中
            tpHovered = mouseX >= tpX - 3 && mouseX <= tpX + tpTextW + 3
                     && mouseY >= y - 1 && mouseY <= y + font.lineHeight + 1;
            g.drawString(font, tp, tpX, y,
                    tpHovered ? FontPalette.title() : FontPalette.link(), false);
        }

        if (!right.isEmpty()) {
            int rx = x + w - rightW;
            if (c.unread() > 0) {
                // 未读用红底白字，一眼能看见；纯红字在深色壁纸上容易糊掉。
                // 底走换肤，与消息通知里的角标共用同一张贴图
                PhoneSkin.drawOrFill(g, PhoneSkin.Element.UNREAD_BADGE,
                        rx - 1, y - 1, x + w - (rx - 1), font.lineHeight + 1, COLOR_UNREAD_BG);
                g.drawString(font, right, rx + 1, y, PhoneTheme.FONT_COLOR_BADGE, false);
            } else {
                g.drawString(font, right, rx, y, colorTime(), false);
            }
        }

        // ---- 第二行：最后一条消息预览 ----
        String preview = c.lastText().isEmpty()
                ? Component.translatable("mcphone.chat.no_message").getString()
                : c.lastText();
        g.drawString(font, GuiUtil.truncate(font, preview, w - (nameX - x)),
                nameX, y + font.lineHeight + 1, colorPreview(), false);

        return tpHovered;
    }

    // ============================================================
    //  鼠标
    // ============================================================

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        if (addContactHovered) {
            pendingAddContact = true;
            return true;
        }

        List<ConversationSummary> list = ChatClientCache.getConversations();

        // 传送必须抢在"点进会话"之前：它的点击区整个落在那一行里面，
        // 后判的话点传送会连带把会话也打开
        if (teleportHoveredIdx >= 0 && teleportHoveredIdx < list.size()) {
            // 只发包，不改本地状态：能不能传全由服务端说了算，
            // 与加好友、解除好友同一条规矩
            PacketDistributor.sendToServer(
                    new TeleportToFriendPacket(list.get(teleportHoveredIdx).id()));
            pendingClose = true;
            return true;
        }

        if (hoveredIdx >= 0 && hoveredIdx < list.size()) {
            pendingOpen = list.get(hoveredIdx).id();
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double scrollY) {
        if (scrollY > 0 && scrollOffset > 0) {
            scrollOffset--;
            return true;
        }
        if (scrollY < 0 && scrollOffset < ChatClientCache.getConversations().size() - 1) {
            scrollOffset++;
            return true;
        }
        return false;
    }

    // ============================================================
    //  内部
    // ============================================================

    /** 到点就再拉一次会话摘要 */
    private void maybeRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < REFRESH_INTERVAL_MS) return;

        lastRequestMs = now;
        PacketDistributor.sendToServer(new RequestConversationsPacket());
    }

    /** 每行两行文字：名字 + 预览 */
    private static int rowHeight(Font font) {
        return font.lineHeight * 2 + 4;
    }

    /**
     * 限制滚动位置。
     *
     * 列表变短时（联系人被删、会话减少）必须夹紧，否则会滚到空白处，
     * 玩家以为列表是空的。
     */
    private void clampScroll(int total, int availableHeight, Font font) {
        int visible = Math.max(1, availableHeight / rowHeight(font));
        int maxOffset = Math.max(0, total - visible);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
        if (scrollOffset < 0) scrollOffset = 0;
    }

    /** 未读数超过 99 就显示 99+，否则一个三位数会把整行挤变形 */
    private static String unreadLabel(int unread) {
        return unread > 99 ? "99+" : String.valueOf(unread);
    }

}
