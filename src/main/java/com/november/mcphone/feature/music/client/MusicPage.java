package com.november.mcphone.feature.music.client;

import com.november.mcphone.core.client.ClientConfig;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.feature.music.PlayMode;
import com.november.mcphone.feature.music.Track;
import com.november.mcphone.feature.music.client.playback.AudioDecoders;
import com.november.mcphone.feature.music.client.playback.LocalPlayback;
import com.november.mcphone.feature.music.client.source.MusicSources;
import com.november.mcphone.feature.music.net.DiscActionPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 音乐 App 的界面 —— 曲库列表 ＋ 底部一条迷你播放条。
 *
 * ============================================================
 * 为什么是一页，不是"列表页 + 正在播放页"
 * ============================================================
 *
 * 两页要多一个 Mode、多一套导航、多一处"从哪儿退回哪儿"。而手机上真正
 * 常用的操作是【一边看列表一边控制播放】，分成两页反而要来回切。
 *
 * 底部那一条把当前曲目、进度和四个键都收在 28 像素里，列表照样占满剩下
 * 的地方。这就是现在手机播放器的通行做法。
 *
 * 顶上还有一条 18 像素的唱片仓，那是"外放"那一半的入口，与下面的曲库
 * 各管各的：唱片仓走服务端、周围人听得见、只有播和停；曲库走本地解码、
 * 只有自己听得见、能暂停能看进度。两者可以同时响，就像真的一边戴耳机
 * 一边开外放——没必要禁止，玩家自己会关掉一个。
 *
 * "各管各的"到 1.5.2 才算真的做到：在那之前存档里【所有】唱片都被塞进
 * 曲库当曲目，玩家自己那几首歌被几十张唱片埋着。现在曲库只有
 * config/mcphone/music/ 下的文件，唱片只在上面那一条里出现。
 *
 * ============================================================
 * 界面不持有播放状态
 * ============================================================
 *
 * 现在放到第几首、是不是暂停、放了多久，全都现问 {@link MusicController}
 * 与 {@link LocalPlayback}。本类只有滚动位置和悬停这两样纯界面的东西。
 *
 * 旧版本把 playingIdx / isPlaying 记在界面里，于是音频线程一改状态，
 * 界面就与真实情况对不上——那是它"▶ 一直亮着"的根因。
 *
 * ============================================================
 * 点击区按【谁】记，不按第几行
 * ============================================================
 *
 * 与会话列表同一条规矩（见 ChatList）：列表会因为刷新目录而变，记下标
 * 会点到别人。这里记的是 Track 本身。
 */
public final class MusicPage {

    /** 左右内边距 */
    private static final int PAD = 4;

    /** 一行曲目的高度：一行字加上下各 1 像素 */
    private static final int ROW_EXTRA = 2;

    /** 底部播放条的高度：曲名一行 + 进度条 + 按钮一行 */
    private static final int BAR_H = 28;

    /** 唱片仓那一条的高度。16 是物品图标的边长，上下各留 1 */
    private static final int BAY_H = 18;

    /** 物品图标边长。原版物品贴图就是 16×16，缩放会糊 */
    private static final int ITEM_SIZE = 16;

    /** 播放条上那四个键的边长。9 ＝ 与行内文字目测等高 */
    private static final int BTN = 9;

    /** 键之间的空隙 */
    private static final int BTN_GAP = 6;

    /** 进度条高度。2 像素：细到不抢地方，又粗到看得见 */
    private static final int PROGRESS_H = 2;

    /** 点击区四边各放宽多少，与传送图标同一个理由：9 像素太难点中 */
    private static final int HIT_PAD = 3;

    private int scrollOffset;

    /** 鼠标停在哪一首上，null 表示没有。记曲目不记下标，理由见类注释 */
    private Track hoveredTrack;

    /** 播放条上四个键的命中矩形，绘制时算出、点击时用 */
    private int prevX, playX, nextX, modeX, btnY;
    private boolean barVisible;

    /** 待消费的"刷新曲库"请求 */
    private boolean refreshHovered;

    /** 播放条的上沿，滚轮判定要用 */
    private int barTop;

    /** 唱片仓上三个点击区的横坐标与上沿，绘制时算出、点击时用 */
    private int bayY, discToggleX, discEjectX;
    private boolean bayEmpty;

    /**
     * 音量回显到什么时候为止。
     *
     * 滚轮调音量之后，曲名那一行临时换成"音量 80%"再变回去。不回显的话
     * 玩家滚了半天不知道调到了哪儿——而这条上没地方常驻一个音量数字。
     */
    private long volumeShownUntil;

    /** 音量回显停留多久 */
    private static final long VOLUME_HINT_MS = 1500L;

    /** 滚轮一格调多少音量 */
    private static final float VOLUME_STEP = 0.05F;

    // ============================================================
    //  生命周期
    // ============================================================

    /** 进入 App：重扫一次曲库，新丢进目录的歌立刻就在 */
    public void open() {
        scrollOffset = 0;
        hoveredTrack = null;
        MusicSources.refreshAll();

        // 唱片仓的真值在服务端，进来先要一份。不要的话界面会先显示上一次
        // 的快照——玩家可能中途把唱片取走过（死亡掉落、别的界面操作）
        PacketDistributor.sendToServer(new DiscActionPacket(DiscActionPacket.Action.QUERY));
    }

    /**
     * 离开 App —— **不停音乐**。
     *
     * 这是刻意的：手机上退出播放器界面，歌当然还在放。真要停有播放键。
     * 退出世界时才由 LocalPlayback.shutdown 收掉。
     */
    public void close() {
        hoveredTrack = null;
    }

    // ============================================================
    //  渲染
    // ============================================================

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;
        final int screenBottom = phoneTop + screenH - navH;
        int y = phoneTop + statusH + 4;

        y = renderHeader(g, font, x, y, w, mouseX, mouseY);
        y = renderDiscBay(g, font, x, y, w, mouseX, mouseY);

        // 有东西在放才留出底部那一条，否则列表白白少一截
        barVisible = MusicController.current() != null;
        int listBottom = barVisible ? screenBottom - BAR_H : screenBottom;

        List<Track> tracks = MusicSources.allTracks();
        if (tracks.isEmpty()) {
            renderEmpty(g, font, x, y, w);
            hoveredTrack = null;
        } else {
            clampScroll(tracks.size(), listBottom - y, font);
            renderRows(g, font, tracks, x, y, w, listBottom, mouseX, mouseY);
        }

        barTop = screenBottom - BAR_H;
        if (barVisible) {
            renderBar(g, font, x, barTop, w, mouseX, mouseY);
        }
    }

    /** 标题行：左边 App 名，右边刷新 */
    private int renderHeader(GuiGraphics g, Font font, int x, int y, int w,
                             int mouseX, int mouseY) {
        g.drawString(font, Component.translatable("mcphone.app.music").getString(),
                x, y, FontPalette.title(), true);

        String refresh = Component.translatable("mcphone.music.refresh").getString();
        int rw = font.width(refresh);
        int rx = x + w - rw;
        refreshHovered = GuiUtil.hit(mouseX, mouseY, rx - HIT_PAD, y - 2,
                rw + HIT_PAD * 2, font.lineHeight + 4);
        g.drawString(font, refresh, rx, y,
                refreshHovered ? FontPalette.title() : FontPalette.link(), false);

        y += font.lineHeight + 4;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        return y + 4;
    }

    /**
     * 唱片仓 —— 外放那一半的入口。
     *
     * 空着时整条都是"放入"的点击区：那时候这一条只有一个意思，把点击区
     * 缩小到某个小按钮上纯属为难人。
     *
     * 放着唱片时右边两个键：播放/停止，以及取出。没有暂停继续——原版音效
     * 系统只有开始和停止，给一个按下去会从头开始的"继续"比不给更糟。
     */
    private int renderDiscBay(GuiGraphics g, Font font, int x, int y, int w,
                              int mouseX, int mouseY) {
        bayY = y;
        bayEmpty = !DiscClientCache.hasDisc();

        boolean hovered = GuiUtil.hit(mouseX, mouseY, x, y, w, BAY_H);
        if (hovered && bayEmpty) {
            g.fill(x, y, x + w, y + BAY_H, PhoneTheme.COLOR_ROW_HOVER);
        }

        if (bayEmpty) {
            // 空仓：画一个虚位 + 一句话。用与主屏拖动空槽同一块兜底色，
            // 玩家对"这里可以放东西"的观感是一致的
            PhoneSkin.drawOrFill(g, PhoneSkin.Element.HOME_DROP_SLOT,
                    x + 1, y + 1, ITEM_SIZE, ITEM_SIZE, PhoneTheme.COLOR_APP_DROP_SLOT);

            int textX = x + ITEM_SIZE + 4;
            g.drawString(font, GuiUtil.truncate(font,
                            Component.translatable("mcphone.music.disc.insert_hint").getString(),
                            w - (textX - x)),
                    textX, y + (BAY_H - font.lineHeight) / 2, FontPalette.dim(), false);

            g.fill(x, y + BAY_H, x + w, y + BAY_H + 1, PhoneTheme.COLOR_DIVIDER);
            return y + BAY_H + 4;
        }

        // ---- 仓里有唱片 ----
        ItemStack disc = DiscClientCache.getDisc();
        g.renderItem(disc, x + 1, y + 1);

        boolean playing = DiscClientCache.isPlaying();
        discEjectX = x + w - BTN;
        discToggleX = discEjectX - BTN - BTN_GAP;

        int textX = x + ITEM_SIZE + 4;
        int textW = discToggleX - textX - 2;
        g.drawString(font, GuiUtil.truncate(font, discTitle(disc), textW),
                textX, y + (BAY_H - font.lineHeight) / 2,
                playing ? FontPalette.title() : FontPalette.body(), false);

        int btnY2 = y + (BAY_H - BTN) / 2;
        drawButton(g, font,
                playing ? PhoneSkin.Element.MUSIC_PAUSE : PhoneSkin.Element.MUSIC_PLAY,
                playing ? "■" : "▶", discToggleX, btnY2, mouseX, mouseY);
        drawButton(g, font, PhoneSkin.Element.MUSIC_EJECT, "⏏",
                discEjectX, btnY2, mouseX, mouseY);

        g.fill(x, y + BAY_H, x + w, y + BAY_H + 1, PhoneTheme.COLOR_DIVIDER);
        return y + BAY_H + 4;
    }

    /**
     * 唱片显示什么名字。
     *
     * 优先曲子的名称（"C418 - cat"），那是玩家在唱片机上看到的那一句。
     * 取不到才退回物品名（"音乐唱片"）——数据包可能定义了没有描述的唱片。
     */
    private static String discTitle(ItemStack disc) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null) {
            var song = net.minecraft.world.item.JukeboxSong
                    .fromStack(mc.level.registryAccess(), disc);
            if (song.isPresent()) return song.get().value().description().getString();
        }
        return disc.getHoverName().getString();
    }

    /** 空曲库：告诉玩家往哪儿放歌、放什么格式 */
    private void renderEmpty(GuiGraphics g, Font font, int x, int y, int w) {
        g.drawString(font, Component.translatable("mcphone.music.empty").getString(),
                x, y, FontPalette.subtle(), false);
        y += font.lineHeight + 2;

        // 提示比屏幕宽，按词换行；硬截断会把路径截掉一半，那句提示就废了
        Component hint = Component.translatable("mcphone.music.empty_hint",
                AudioDecoders.supportedNames());
        for (var line : font.split(hint, w)) {
            g.drawString(font, line, x, y, FontPalette.dim(), false);
            y += font.lineHeight;
        }
    }

    private void renderRows(GuiGraphics g, Font font, List<Track> tracks,
                            int x, int y, int w, int bottom, int mouseX, int mouseY) {
        final int rowH = font.lineHeight + ROW_EXTRA;
        hoveredTrack = null;

        Track playing = MusicController.current();

        for (int i = scrollOffset; i < tracks.size(); i++) {
            if (y + rowH > bottom) break;

            Track t = tracks.get(i);
            boolean hovered = GuiUtil.hit(mouseX, mouseY, x, y, w, rowH);
            if (hovered) {
                hoveredTrack = t;
                g.fill(x, y, x + w, y + rowH, PhoneTheme.COLOR_ROW_HOVER);
            }

            boolean isCurrent = playing != null && playing.key().equals(t.key());
            if (isCurrent) {
                g.fill(x, y, x + w, y + rowH, PhoneTheme.COLOR_ROW_ACTIVE);
            }

            // 上次没放出来的，这一行变灰；停上去就用这一行把原因说了。
            // 借这一行说而不是另开一个提示层，与会话列表上那个传送提示同一个
            // 做法（见 ChatList）：地方本来就在，不必为一句话另外挤空间
            Component problem = MusicProblems.of(t);

            // 行首那个音符纯粹是装饰，旧界面就有，习惯留着。1.5.2 之前它还
            // 兼着区分来源（♫ 是原版唱片、♪ 是本地文件），现在曲库里只剩
            // 本地文件了，那个区分也就没有意义
            String label = (problem != null && hovered)
                    ? "♪ " + problem.getString()
                    : "♪ " + t.title();

            int color;
            if (problem != null) {
                color = hovered ? FontPalette.notice() : FontPalette.dim();
            } else {
                color = isCurrent ? FontPalette.title() : FontPalette.body();
            }

            g.drawString(font, GuiUtil.truncate(font, label, w - 4), x + 2, y + 1,
                    color, false);
            y += rowH;
        }
    }

    /** 底部播放条：曲名、进度、四个键 */
    private void renderBar(GuiGraphics g, Font font, int x, int y, int w,
                           int mouseX, int mouseY) {
        Track track = MusicController.current();
        if (track == null) return;

        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 3;

        // ---- 曲名，刚调过音量时临时改显音量 ----
        boolean showVolume = System.currentTimeMillis() < volumeShownUntil;
        String line = showVolume
                ? Component.translatable("mcphone.music.volume",
                        Math.round(LocalPlayback.getVolume() * 100)).getString()
                : track.title();
        g.drawString(font, GuiUtil.truncate(font, line, w), x, y,
                showVolume ? FontPalette.link() : FontPalette.title(), false);
        y += font.lineHeight + 2;

        // ---- 进度条 ----
        renderProgress(g, track, x, y, w);
        y += PROGRESS_H + 3;

        // ---- 四个键：三个居中，模式键靠右 ----
        btnY = y;
        int groupW = BTN * 3 + BTN_GAP * 2;
        prevX = x + (w - groupW) / 2;
        playX = prevX + BTN + BTN_GAP;
        nextX = playX + BTN + BTN_GAP;
        modeX = x + w - BTN;

        boolean playing = LocalPlayback.isPlaying();
        drawButton(g, font, PhoneSkin.Element.MUSIC_PREV, "⏮", prevX, y, mouseX, mouseY);
        drawButton(g, font,
                playing ? PhoneSkin.Element.MUSIC_PAUSE : PhoneSkin.Element.MUSIC_PLAY,
                playing ? "⏸" : "▶", playX, y, mouseX, mouseY);
        drawButton(g, font, PhoneSkin.Element.MUSIC_NEXT, "⏭", nextX, y, mouseX, mouseY);

        PlayMode mode = MusicController.getMode();
        drawButton(g, font, modeElement(mode), mode.glyph(), modeX, y, mouseX, mouseY);
    }

    /**
     * 进度条。
     *
     * 时长未知时（OGG 不读完整个文件拿不到时长）画一条走到底的底色，
     * 不画进度——画一个瞎猜的比例比不画更误导。
     */
    private void renderProgress(GuiGraphics g, Track track, int x, int y, int w) {
        g.fill(x, y, x + w, y + PROGRESS_H, PhoneTheme.COLOR_MUSIC_PROGRESS_BG);

        if (!track.hasDuration()) return;

        long elapsed = LocalPlayback.elapsedMillis();
        float ratio = Math.clamp(elapsed / (float) track.durationMs(), 0.0F, 1.0F);
        g.fill(x, y, x + (int) (w * ratio), y + PROGRESS_H, PhoneTheme.COLOR_MUSIC_PROGRESS);
    }

    /** 贴图优先、字符兜底、悬停铺一层高亮 —— 与导航栏三个键同一套 */
    private void drawButton(GuiGraphics g, Font font, PhoneSkin.Element element,
                            String glyph, int x, int y, int mouseX, int mouseY) {
        boolean hovered = GuiUtil.hit(mouseX, mouseY,
                x - HIT_PAD, y - HIT_PAD, BTN + HIT_PAD * 2, BTN + HIT_PAD * 2);
        if (hovered) {
            g.fill(x - HIT_PAD, y - HIT_PAD, x + BTN + HIT_PAD, y + BTN + HIT_PAD,
                    PhoneTheme.COLOR_HOVER_STRONG);
        }

        if (PhoneSkin.draw(g, element, x, y, BTN, BTN)) return;

        g.drawString(font, glyph, x + (BTN - font.width(glyph)) / 2, y,
                hovered ? FontPalette.title() : FontPalette.link(), false);
    }

    private static PhoneSkin.Element modeElement(PlayMode mode) {
        return switch (mode) {
            case LIST_LOOP -> PhoneSkin.Element.MUSIC_MODE_LIST_LOOP;
            case SINGLE_LOOP -> PhoneSkin.Element.MUSIC_MODE_SINGLE_LOOP;
            case SHUFFLE -> PhoneSkin.Element.MUSIC_MODE_SHUFFLE;
        };
    }

    // ============================================================
    //  鼠标
    // ============================================================

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        if (refreshHovered) {
            // 顺手把"放不了"的旧结论清掉：玩家多半就是换掉了那个文件才来
            // 点刷新的，还留着旧结论会让他以为没生效
            MusicProblems.clearAll();
            MusicSources.refreshAll();
            return true;
        }

        // 唱片仓在列表上面，先判它
        if (hitDiscBay(mx, my)) return true;

        // 播放条要抢在列表之前判：它盖在列表下沿，后判会连带点到某一行
        if (barVisible && hitButtons(mx, my)) return true;

        if (hoveredTrack != null) {
            List<Track> tracks = MusicSources.allTracks();
            int at = indexOf(tracks, hoveredTrack);
            if (at >= 0) MusicController.play(tracks, at);
            return true;
        }
        return false;
    }

    /**
     * 唱片仓上的点击。
     *
     * 只发包、不改本地状态：能不能放、能不能取全由服务端说了算，它处理完
     * 会回一份最新状态。本地抢先改的话，一旦服务端因为背包满了拒绝了，
     * 界面就会显示唱片已经取出的假象——与加好友那边同一条规矩。
     */
    private boolean hitDiscBay(double mx, double my) {
        if (my < bayY || my >= bayY + BAY_H) return false;

        if (bayEmpty) {
            send(DiscActionPacket.Action.INSERT);
            return true;
        }
        if (hitAt(mx, my, discToggleX, bayY + (BAY_H - BTN) / 2)) {
            send(DiscActionPacket.Action.TOGGLE);
            return true;
        }
        if (hitAt(mx, my, discEjectX, bayY + (BAY_H - BTN) / 2)) {
            send(DiscActionPacket.Action.EJECT);
            return true;
        }
        // 点在这一条的别处：什么都不做。整条都当成"取出"太危险了
        return true;
    }

    private static void send(DiscActionPacket.Action action) {
        PacketDistributor.sendToServer(new DiscActionPacket(action));
    }

    private boolean hitAt(double mx, double my, int bx, int by) {
        return GuiUtil.hit(mx, my, bx - HIT_PAD, by - HIT_PAD,
                BTN + HIT_PAD * 2, BTN + HIT_PAD * 2);
    }

    private boolean hitButtons(double mx, double my) {
        if (hit(mx, my, prevX)) { MusicController.previous(); return true; }
        if (hit(mx, my, playX)) { MusicController.togglePlayPause(); return true; }
        if (hit(mx, my, nextX)) { MusicController.next(); return true; }
        if (hit(mx, my, modeX)) { MusicController.cycleMode(); return true; }
        return false;
    }

    private boolean hit(double mx, double my, int bx) {
        return GuiUtil.hit(mx, my, bx - HIT_PAD, btnY - HIT_PAD,
                BTN + HIT_PAD * 2, BTN + HIT_PAD * 2);
    }

    /**
     * 滚轮翻列表。
     *
     * 旧版本根本没接这个方法，19 首原版唱片加上自定义文件，一屏之外的
     * 全都够不着——这是它"没法用"的头号原因。
     */
    public boolean mouseScrolled(double scrollY, double mouseY) {
        // 停在播放条上滚＝调音量。那一条上没有列表可滚，这块地方空着也是空着，
        // 而音量是唯一还没有入口的常用控制
        if (barVisible && mouseY >= barTop) {
            LocalPlayback.setVolume(LocalPlayback.getVolume() + (float) scrollY * VOLUME_STEP);
            ClientConfig.saveMusicVolume(LocalPlayback.getVolume());
            volumeShownUntil = System.currentTimeMillis() + VOLUME_HINT_MS;
            return true;
        }

        if (scrollY > 0 && scrollOffset > 0) {
            scrollOffset--;
            return true;
        }
        if (scrollY < 0 && scrollOffset < MusicSources.allTracks().size() - 1) {
            scrollOffset++;
            return true;
        }
        return false;
    }

    // ============================================================
    //  内部
    // ============================================================

    private static int indexOf(List<Track> tracks, Track target) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).key().equals(target.key())) return i;
        }
        return -1;
    }

    /** 列表变短（删了文件、切了音源）时必须夹紧，否则会滚到一片空白 */
    private void clampScroll(int total, int availableHeight, Font font) {
        int visible = Math.max(1, availableHeight / (font.lineHeight + ROW_EXTRA));
        scrollOffset = Math.clamp(scrollOffset, 0, Math.max(0, total - visible));
    }
}
