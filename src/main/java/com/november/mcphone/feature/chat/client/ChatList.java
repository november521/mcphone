package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.ServerConfig;
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
 * 为什么要定时刷新
 *
 * 新消息是服务端主动推的，不需要轮询。但【在线状态】和【未读数】不是：
 * 联系人上线下线不会通知我们，未读数也只有服务端算得出。所以列表打开
 * 期间每隔几秒拉一次摘要。
 *
 * 只在列表可见时才拉——关掉 App 就停，不会在后台空转。
 *
 * 一行里有两个点击区
 *
 * 点这一行的任何地方＝进会话；点右下角那个小图标＝传送到对方身边
 * （仅限在线的人）。后者的点击区整个落在前者里面，所以判定顺序反不得：
 * 先问图标，再问行。反过来的话，点图标会连带把会话也打开。
 *
 * 悬停记的是【谁】，不是【第几行】
 *
 * 列表每 3 秒被服务端整份换掉，而且换的时机与玩家点击完全无关：有人发来
 * 一条消息，那一行就会排到最前，整列跟着重排。
 *
 * 若把"鼠标停在第几行"记成一个下标，点击时再拿它去索引【那一刻】的列表，
 * 中间只要插进一次替换，点到的就是另一个人。这一列里现在有两个不可撤销
 * 的动作——传送和解除好友——点错的后果一个是被传走，一个是好友没了。
 *
 * 记 UUID 就没有这个问题：点击时直接拿它发包，根本不碰列表。对方要是刚好
 * 被解除了好友，服务端那三道校验会拦下来，客户端不必自己防。
 *
 * 这个按钮为什么是图标不是文字
 *
 * 1.4.5 用的是「→传送」四个字符。120px 宽的屏幕上它太贵：中文两个字加
 * 箭头走掉近三成行宽，逼得名字截到认不出人，还得专门写一条"挤不下就砍掉
 * 时间戳"的让路规则（1.4.7）。
 *
 * 1.4.9 试过改成点头像，版面成本是零，但误点的代价太大——头像那一块同时
 * 是"这是谁"和"点我传送"两个意思，而点错的后果是人真的被传走，撤不回来。
 *
 * 现在是 7×7 的图标，而且放在行的【右下角】——名字那一行一个像素都不用让：
 * 右上角归未读数与时间，右下角在这个两行版式里本来就是空的。掏的是第二行
 * 消息预览的宽度，那是三样东西里最不要紧的一样。
 *
 * 它又是一块专门的、只有一个意思的区域，误点不了。让路规则跟着删了。
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

    /**
     * 传送图标的边长。
     *
     * 7＝与旁边的字目测等高：原版字体行高 9、字形格 8，而大写字母的实际
     * 笔画高度就是 7。取 8 或 9 的话图标会比同一行的字明显高出一截。
     *
     * 改这个数就要连贴图一起改：这里不做平滑缩放，尺寸对不上会隔行抽像素。
     */
    private static final int TP_ICON_SIZE = 7;

    /** 传送图标与它左边那行消息预览之间的空隙 */
    private static final int TP_GAP = 3;

    /**
     * 图标的点击区四边各放宽多少。
     *
     * 8px 见方按边界严丝合缝地判定太难点中，与标题栏那个"+"同一套做法。
     */
    private static final int TP_HIT_PAD = 3;

    // ---- 颜色 ----
    private static int colorName() { return FontPalette.title(); }
    private static int colorPreview() { return FontPalette.preview(); }
    private static int colorTime() { return FontPalette.timestamp(); }
    private static final int COLOR_UNREAD_BG = PhoneTheme.COLOR_UNREAD_BADGE;
    private static final int COLOR_ROW_HOVER = PhoneTheme.COLOR_ROW_HOVER;

    private long lastRequestMs;
    private int scrollOffset;
    private boolean addContactHovered;

    /** 鼠标停在谁那一行上，null 表示没有。记人不记下标，理由见类注释 */
    private UUID hoveredPeer;

    /** 待消费的"打开某个会话"请求 */
    private UUID pendingOpen;

    /** 待消费的"打开加联系人界面"请求 */
    private boolean pendingAddContact;

    /** 鼠标停在谁那一行的传送图标上，null 表示没有 */
    private UUID teleportHoveredPeer;

    /** 待消费的"把手机关掉"请求 */
    private boolean pendingClose;

    // ============================================================
    //  生命周期
    // ============================================================

    /** 进入会话列表：立刻拉一次，不必等定时刷新 */
    public void open() {
        scrollOffset = 0;
        hoveredPeer = null;
        lastRequestMs = 0L;      // 置零＝下一帧立即请求
        pendingOpen = null;
        pendingAddContact = false;
        teleportHoveredPeer = null;
        pendingClose = false;
    }

    /** 离开会话列表：停止定时刷新 */
    public void close() {
        hoveredPeer = null;
        addContactHovered = false;
        teleportHoveredPeer = null;
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
            hoveredPeer = null;
            teleportHoveredPeer = null;
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
        hoveredPeer = null;
        teleportHoveredPeer = null;

        for (int i = scrollOffset; i < list.size(); i++) {
            if (y + rowH > bottom) break;

            ConversationSummary c = list.get(i);
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + rowH;
            if (hovered) {
                hoveredPeer = c.id();
                g.fill(x, y, x + w, y + rowH, COLOR_ROW_HOVER);
            }

            // 悬停判定与绘制在同一处完成：按钮画在哪儿，点击区就在哪儿，
            // 两处各算一遍迟早会算出不一样的结果
            if (renderRow(g, font, c, x, y, w, mouseX, mouseY)) teleportHoveredPeer = c.id();
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

        // 名字占满这一行的剩余宽度：传送图标在第二行的右下角，不与它争
        int nameX = x + AVATAR_SIZE + AVATAR_GAP;
        int nameMaxW = w - (nameX - x) - rightW - 4;
        String name = GuiUtil.truncate(font, c.name(), nameMaxW);
        g.drawString(font, name, nameX, y, colorName(), false);

        // ---- 传送图标：行的右下角，只给在线的人 ----
        // 离线传不过去，画一个点了没反应的按钮比不画更让人困惑。
        // 服主把功能关掉时同理：那是服务端配置，NeoForge 会同步给客户端，
        // 所以这里读得到。真正的拦截在服务端，界面只是不给入口
        //
        // 位置先算出来、图最后再画：第二行的预览文字要按它让出的宽度截断，
        // 而悬停与否又决定那行写的是预览还是提示，顺序绕不开
        boolean canTeleport = c.online() && ServerConfig.allowFriendTeleport();
        int secondLineY = y + font.lineHeight + 1;
        int tpW = 0;
        int tpX = 0;
        int tpY = 0;
        boolean tpHovered = false;
        if (canTeleport) {
            // 往里缩 TP_HIT_PAD，不贴着行的右边缘：悬停高亮铺的是【点击区】，
            // 贴边的话那块高亮会比整行的高亮宽出 3px，凸在外面很显眼
            tpW = TP_ICON_SIZE + TP_HIT_PAD + TP_GAP;
            tpX = x + w - TP_ICON_SIZE - TP_HIT_PAD;
            // 与第二行的字目测对齐：两者都是 7 高，直接同一条基线
            tpY = secondLineY + (font.lineHeight - TP_ICON_SIZE) / 2;
            tpHovered = GuiUtil.hit(mouseX, mouseY,
                    tpX - TP_HIT_PAD, tpY - TP_HIT_PAD,
                    TP_ICON_SIZE + TP_HIT_PAD * 2, TP_ICON_SIZE + TP_HIT_PAD * 2);
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

        // ---- 第二行：最后一条消息预览，停在传送图标上时改说传送 ----
        // 7 像素的图标画什么都得让人猜，得有个地方把话说明白。借这一行说，
        // 是因为它本来就在，不必为提示另外挤出空间；预览晚一眼看也不打紧
        String preview;
        int previewColor;
        if (tpHovered) {
            preview = Component.translatable("mcphone.chat.teleport_hint").getString();
            previewColor = FontPalette.link();
        } else {
            preview = c.lastText().isEmpty()
                    ? Component.translatable("mcphone.chat.no_message").getString()
                    : c.lastText();
            previewColor = colorPreview();
        }
        g.drawString(font, GuiUtil.truncate(font, preview, w - (nameX - x) - tpW),
                nameX, secondLineY, previewColor, false);

        if (canTeleport) renderTeleportIcon(g, font, tpX, tpY, tpHovered);

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

        // 传送必须抢在"点进会话"之前：图标的点击区整个落在那一行里面，
        // 后判的话点图标会连带把会话也打开
        if (teleportHoveredPeer != null) {
            // 只发包，不改本地状态：能不能传全由服务端说了算，
            // 与加好友、解除好友同一条规矩
            PacketDistributor.sendToServer(new TeleportToFriendPacket(teleportHoveredPeer));
            pendingClose = true;
            return true;
        }

        if (hoveredPeer != null) {
            pendingOpen = hoveredPeer;
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

    /**
     * 那个"传送到他身边"的小图标。
     *
     * 三步与导航栏三个键完全一致：悬停先铺一层高亮底，再画贴图，没有贴图
     * 就画 → 字符兜底。高亮用比整行更浓的一档——整行本来就已经是亮的，
     * 同一个颜色叠上去看不出区别。
     *
     * 兜底画字符而不是色块：一个纯色小方块说明不了它是干什么的，而这个
     * 按钮在贴图到位之前也得能用。
     */
    private static void renderTeleportIcon(GuiGraphics g, Font font,
                                           int x, int y, boolean hovered) {
        if (hovered) {
            g.fill(x - TP_HIT_PAD, y - TP_HIT_PAD,
                    x + TP_ICON_SIZE + TP_HIT_PAD, y + TP_ICON_SIZE + TP_HIT_PAD,
                    PhoneTheme.COLOR_HOVER_STRONG);
        }

        if (PhoneSkin.draw(g, PhoneSkin.Element.CHAT_TELEPORT,
                x, y, TP_ICON_SIZE, TP_ICON_SIZE)) {
            return;
        }

        String glyph = "→";
        g.drawString(font, glyph, x + (TP_ICON_SIZE - font.width(glyph)) / 2, y,
                hovered ? FontPalette.title() : FontPalette.link(), false);
    }

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
