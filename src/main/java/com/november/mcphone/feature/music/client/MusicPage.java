package com.november.mcphone.feature.music.client;

import net.minecraft.util.Mth;
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
import com.november.mcphone.feature.music.net.OpenDiscBayPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.november.mcphone.core.net.MCphoneNetwork;

import java.util.List;

/**
 * 音乐 App 的界面 —— 顶上一条唱片仓（外放，走服务端）＋ 曲库列表（本地解码，只有自己听得见）＋ 底部一条迷你播放条。
 * 界面不持有播放状态，全都现问 {@link MusicController} 与 {@link LocalPlayback}；点击区按 Track 记，不按下标，列表刷新后不会点错。
 */
public final class MusicPage {

    private static final int PAD = 4;

    /** 一行曲目的高度：一行字加上下各 1 像素 */
    private static final int ROW_EXTRA = 2;

    /** 播放条高度：曲名 + 进度条 + 按钮，末尾必须再留一个 HIT_PAD，否则键的悬停高亮会铺进导航栏（换半透明 nav_bar 贴图就露馅） */
    private static final int BAR_H = 31;

    /** 唱片仓那一条的高度。16 是物品图标的边长，上下各留 1 */
    private static final int BAY_H = 18;

    /** 物品图标边长。原版物品贴图就是 16×16，缩放会糊 */
    private static final int ITEM_SIZE = 16;

    /** 键的边长，与行内文字目测等高 */
    private static final int BTN = 9;

    private static final int BTN_GAP = 6;

    private static final int PROGRESS_H = 2;

    /** 点击区四边各放宽多少：9 像素的键太难点中 */
    private static final int HIT_PAD = 3;

    /** 靠右的键往里缩多少。必须正好等于 HIT_PAD：悬停高亮铺的是点击区，贴边放会铺到机身边框上 */
    private static final int EDGE_INSET = HIT_PAD;

    private int scrollOffset;

    /** scrollOffset 的上限，渲染时算出、滚轮判定时用 */
    private int maxScroll;

    /** 鼠标停在哪一首上，null 表示没有 */
    private Track hoveredTrack;

    /** 播放条上四个键的命中矩形，绘制时算出、点击时用 */
    private int prevX, playX, nextX, modeX, btnY;
    private boolean barVisible;

    private boolean refreshHovered;

    /** 播放条的上沿，滚轮判定要用 */
    private int barTop;

    /** 唱片仓上几个点击区的横坐标与上沿，绘制时算出、点击时用 */
    private int bayY, discToggleX, discEjectX, discBackpackX;
    private boolean bayEmpty;

    /** 滚轮调音量后，曲名那一行临时换成音量显示，到这个毫秒时间戳为止 */
    private long volumeShownUntil;

    private static final long VOLUME_HINT_MS = 1500L;

    private static final float VOLUME_STEP = 0.05F;

    /** 进入 App：重扫一次曲库 */
    public void open() {
        scrollOffset = 0;
        hoveredTrack = null;
        MusicSources.refreshAll();

        // 唱片仓的真值在服务端，进来先要一份，否则会先显示上一次的快照
        MCphoneNetwork.sendToServer(new DiscActionPacket(DiscActionPacket.Action.QUERY));
    }

    /** 离开 App 刻意不停音乐；退出世界时才由 LocalPlayback.shutdown 收掉 */
    public void close() {
        hoveredTrack = null;
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;
        final int screenBottom = phoneTop + screenH - navH;
        int y = phoneTop + statusH + 4;

        y = renderHeader(g, font, x, y, w, mouseX, mouseY);
        y = renderDiscBay(g, font, x, y, w, mouseX, mouseY);

        barVisible = MusicController.current() != null;
        int listBottom = barVisible ? screenBottom - BAR_H : screenBottom;

        List<Track> tracks = MusicSources.allTracks();
        if (tracks.isEmpty()) {
            renderEmpty(g, font, x, y, w);
            hoveredTrack = null;
            maxScroll = 0;
        } else {
            clampScroll(tracks.size(), listBottom - y, font);
            renderRows(g, font, tracks, x, y, w, listBottom, mouseX, mouseY);
        }

        barTop = screenBottom - BAR_H;
        if (barVisible) {
            renderBar(g, font, x, barTop, w, mouseX, mouseY);
        }
    }

    private int renderHeader(GuiGraphics g, Font font, int x, int y, int w,
                             int mouseX, int mouseY) {
        g.drawString(font, Component.translatable("mcphone.app.music").getString(),
                x, y, FontPalette.title(), true);

        String refresh = Component.translatable("mcphone.music.refresh").getString();
        int rw = font.width(refresh);
        int rx = x + w - rw - EDGE_INSET;
        refreshHovered = GuiUtil.hit(mouseX, mouseY, rx - HIT_PAD, y - 2,
                rw + HIT_PAD * 2, font.lineHeight + 4);
        g.drawString(font, refresh, rx, y,
                refreshHovered ? FontPalette.title() : FontPalette.link(), false);

        y += font.lineHeight + 4;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        return y + 4;
    }

    /**
     * 唱片仓：空着时整条都是「放入主手那张」的点击区；开背包键两种状态下都钉在最右。
     * 没有暂停/继续 —— 原版音效系统只有开始和停止。
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
            PhoneSkin.drawOrFill(g, PhoneSkin.Element.HOME_DROP_SLOT,
                    x + 1, y + 1, ITEM_SIZE, ITEM_SIZE, PhoneTheme.COLOR_APP_DROP_SLOT);

            discBackpackX = x + w - BTN - EDGE_INSET;
            int bagY = y + (BAY_H - BTN) / 2;
            boolean bagHovered = hitAt(mouseX, mouseY, discBackpackX, bagY);

            // 停在键上时，这一行改说它是干什么的
            int textX = x + ITEM_SIZE + 4;
            String hint = Component.translatable(bagHovered
                    ? "mcphone.music.disc.backpack_hint"
                    : "mcphone.music.disc.insert_hint").getString();
            g.drawString(font, GuiUtil.truncate(font, hint, textLimit(textX, discBackpackX)),
                    textX, y + (BAY_H - font.lineHeight) / 2,
                    bagHovered ? FontPalette.link() : FontPalette.dim(), false);

            drawButton(g, font, PhoneSkin.Element.MUSIC_BACKPACK, "▤",
                    discBackpackX, bagY, mouseX, mouseY);

            g.fill(x, y + BAY_H, x + w, y + BAY_H + 1, PhoneTheme.COLOR_DIVIDER);
            return y + BAY_H + 4;
        }

        ItemStack disc = DiscClientCache.getDisc();
        g.renderItem(disc, x + 1, y + 1);

        boolean playing = DiscClientCache.isPlaying(gameTime());

        discBackpackX = x + w - BTN - EDGE_INSET;
        discEjectX = discBackpackX - BTN - BTN_GAP;
        discToggleX = discEjectX - BTN - BTN_GAP;

        int btnY2 = y + (BAY_H - BTN) / 2;
        boolean bagHovered = hitAt(mouseX, mouseY, discBackpackX, btnY2);

        int textX = x + ITEM_SIZE + 4;
        int textW = textLimit(textX, discToggleX);
        String line = bagHovered
                ? Component.translatable("mcphone.music.disc.swap_hint").getString()
                : discTitle(disc);
        g.drawString(font, GuiUtil.truncate(font, line, textW),
                textX, y + (BAY_H - font.lineHeight) / 2,
                bagHovered ? FontPalette.link()
                        : (playing ? FontPalette.title() : FontPalette.body()), false);

        drawButton(g, font,
                playing ? PhoneSkin.Element.MUSIC_PAUSE : PhoneSkin.Element.MUSIC_PLAY,
                playing ? "■" : "▶", discToggleX, btnY2, mouseX, mouseY);
        drawButton(g, font, PhoneSkin.Element.MUSIC_EJECT, "⏏",
                discEjectX, btnY2, mouseX, mouseY);
        drawButton(g, font, PhoneSkin.Element.MUSIC_BACKPACK, "▤",
                discBackpackX, btnY2, mouseX, mouseY);

        g.fill(x, y + BAY_H, x + w, y + BAY_H + 1, PhoneTheme.COLOR_DIVIDER);
        return y + BAY_H + 4;
    }

    /** 曲名能占多宽：到最左那个键的【命中区】为止（不是键本身），再退 1 像素 */
    private static int textLimit(int textX, int firstButtonX) {
        return firstButtonX - HIT_PAD - 1 - textX;
    }

    /** 没有世界时返回 Long.MAX_VALUE：判据是「现在 < 终点才算在放」，给 0 会让唱片永远显示在放 */
    private static long gameTime() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc.level == null ? Long.MAX_VALUE : mc.level.getGameTime();
    }

    /**
     * 优先曲子的名称（"C418 - cat"），取不到才退回物品名。
     *
     * 1.21 把唱片曲目抽成了注册表里的 JukeboxSong，要过 registryAccess 去查。
     * 1.20.1 上没有这个东西，曲名就挂在 RecordItem 自己身上（getDisplayName），
     * 不需要世界也不需要注册表访问——所以这边比那边还简单一点。
     */
    private static String discTitle(ItemStack disc) {
        if (disc.getItem() instanceof net.minecraft.world.item.RecordItem record) {
            return record.getDisplayName().getString();
        }
        return disc.getHoverName().getString();
    }

    private void renderEmpty(GuiGraphics g, Font font, int x, int y, int w) {
        g.drawString(font, Component.translatable("mcphone.music.empty").getString(),
                x, y, FontPalette.subtle(), false);
        y += font.lineHeight + 2;

        // 按词换行，硬截断会把路径截掉一半
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

            // 上次没放出来的，这一行变灰；停上去就用这一行把原因说了
            Component problem = MusicProblems.of(t);

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

    private void renderBar(GuiGraphics g, Font font, int x, int y, int w,
                           int mouseX, int mouseY) {
        Track track = MusicController.current();
        if (track == null) return;

        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 3;

        boolean showVolume = System.currentTimeMillis() < volumeShownUntil;
        String line = showVolume
                ? Component.translatable("mcphone.music.volume",
                        Math.round(LocalPlayback.getVolume() * 100)).getString()
                : track.title();
        g.drawString(font, GuiUtil.truncate(font, line, w), x, y,
                showVolume ? FontPalette.link() : FontPalette.title(), false);
        y += font.lineHeight + 2;

        renderProgress(g, track, x, y, w);
        y += PROGRESS_H + 3;

        btnY = y;
        int groupW = BTN * 3 + BTN_GAP * 2;
        prevX = x + (w - groupW) / 2;
        playX = prevX + BTN + BTN_GAP;
        nextX = playX + BTN + BTN_GAP;
        modeX = x + w - BTN - EDGE_INSET;

        boolean playing = LocalPlayback.isPlaying();
        drawButton(g, font, PhoneSkin.Element.MUSIC_PREV, "⏮", prevX, y, mouseX, mouseY);
        drawButton(g, font,
                playing ? PhoneSkin.Element.MUSIC_PAUSE : PhoneSkin.Element.MUSIC_PLAY,
                playing ? "⏸" : "▶", playX, y, mouseX, mouseY);
        drawButton(g, font, PhoneSkin.Element.MUSIC_NEXT, "⏭", nextX, y, mouseX, mouseY);

        PlayMode mode = MusicController.getMode();
        drawButton(g, font, modeElement(mode), mode.glyph(), modeX, y, mouseX, mouseY);
    }

    /** 时长未知时（OGG 问不到）只画底色不画进度：瞎猜的比例比不画更误导 */
    private void renderProgress(GuiGraphics g, Track track, int x, int y, int w) {
        g.fill(x, y, x + w, y + PROGRESS_H, PhoneTheme.COLOR_MUSIC_PROGRESS_BG);

        long total = totalMillis(track);
        if (total <= 0L) return;

        long elapsed = LocalPlayback.elapsedMillis();
        float ratio = Mth.clamp(elapsed / (float) total, 0.0F, 1.0F);
        g.fill(x, y, x + (int) (w * ratio), y + PROGRESS_H, PhoneTheme.COLOR_MUSIC_PROGRESS);
    }

    /** 先问播放层（本地文件的时长要到打开那一刻才知道），再退回曲目自带的（给网络音源留的） */
    private static long totalMillis(Track track) {
        long fromStream = LocalPlayback.durationMillis();
        return fromStream > 0L ? fromStream : track.durationMs();
    }

    /** 贴图优先、字符兜底、悬停铺一层高亮 */
    private void drawButton(GuiGraphics g, Font font, PhoneSkin.Element element,
                            String glyph, int x, int y, int mouseX, int mouseY) {
        boolean hovered = GuiUtil.hit(mouseX, mouseY,
                x - HIT_PAD, y - HIT_PAD, BTN + HIT_PAD * 2, BTN + HIT_PAD * 2);
        if (hovered) {
            g.fill(x - HIT_PAD, y - HIT_PAD, x + BTN + HIT_PAD, y + BTN + HIT_PAD,
                    PhoneTheme.COLOR_HOVER_STRONG);
        }

        if (PhoneSkin.draw(g, element, x, y, BTN, BTN)) return;

        // 兜底字符可能比 9 像素宽，直接居中会算出负偏移把字画到键框左边，夹住下限
        int glyphX = x + Math.max(0, (BTN - font.width(glyph)) / 2);
        g.drawString(font, glyph, glyphX, y,
                hovered ? FontPalette.title() : FontPalette.link(), false);
    }

    private static PhoneSkin.Element modeElement(PlayMode mode) {
        return switch (mode) {
            case LIST_LOOP -> PhoneSkin.Element.MUSIC_MODE_LIST_LOOP;
            case SINGLE_LOOP -> PhoneSkin.Element.MUSIC_MODE_SINGLE_LOOP;
            case SHUFFLE -> PhoneSkin.Element.MUSIC_MODE_SHUFFLE;
        };
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;

        if (refreshHovered) {
            MusicProblems.clearAll();
            MusicSources.refreshAll();
            return true;
        }

        if (hitDiscBay(mx, my)) return true;

        // 播放条必须先于列表判：它盖在列表下沿
        if (barVisible && hitButtons(mx, my)) return true;

        if (hoveredTrack != null) {
            List<Track> tracks = MusicSources.allTracks();
            int at = indexOf(tracks, hoveredTrack);
            if (at >= 0) MusicController.play(tracks, at);
            return true;
        }
        return false;
    }

    /** 只发包、不改本地状态：能不能放/取全由服务端说了算，它会回一份最新状态 */
    private boolean hitDiscBay(double mx, double my) {
        if (my < bayY || my >= bayY + BAY_H) return false;

        final int btnY2 = bayY + (BAY_H - BTN) / 2;

        // 开背包键必须先判：空仓时它盖在「整条＝放入」上面
        if (hitAt(mx, my, discBackpackX, btnY2)) {
            MCphoneNetwork.sendToServer(new OpenDiscBayPacket());
            return true;
        }

        if (bayEmpty) {
            send(DiscActionPacket.Action.INSERT);
            return true;
        }
        if (hitAt(mx, my, discToggleX, btnY2)) {
            send(DiscActionPacket.Action.TOGGLE);
            return true;
        }
        if (hitAt(mx, my, discEjectX, btnY2)) {
            send(DiscActionPacket.Action.EJECT);
            return true;
        }
        // 点在别处什么都不做，整条当成「取出」太危险
        return true;
    }

    private static void send(DiscActionPacket.Action action) {
        MCphoneNetwork.sendToServer(new DiscActionPacket(action));
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

    public boolean mouseScrolled(double scrollY, double mouseY) {
        // 停在播放条上滚＝调音量
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
        if (scrollY < 0 && scrollOffset < maxScroll) {
            scrollOffset++;
            return true;
        }
        return false;
    }

    private static int indexOf(List<Track> tracks, Track target) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).key().equals(target.key())) return i;
        }
        return -1;
    }

    /** 列表变短时必须夹紧，否则会滚到一片空白；顺手把上限记下来给滚轮用 */
    private void clampScroll(int total, int availableHeight, Font font) {
        int visible = Math.max(1, availableHeight / (font.lineHeight + ROW_EXTRA));

        maxScroll = Math.max(0, total - visible);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
    }
}
