package com.november.mcphone.feature.music.client;

import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.JukeboxSong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.sound.sampled.*;

/**
 * 音乐播放器 —— 由 PhoneScreen 嵌入渲染。
 *
 * 功能:
 * - 原版唱片列表 (读取 JukeboxSong 注册表)
 * - 自定义 WAV 文件 (config/mcphone/music/)
 * - 点击播放/暂停
 *
 * 自定义音乐放入: config/mcphone/music/
 * 支持格式: WAV (PCM 16-bit)
 */
public final class MusicPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("mcphone/MusicPlayer");

    public record MusicTrack(String name, String soundId, boolean isCustom) {}

    private static final int PAD = 6;

    private final List<MusicTrack> tracks = new ArrayList<>();
    private boolean scanned = false;

    private int scrollOffset = 0;
    private int hoveredIdx = -1;
    private int playingIdx = -1;
    private boolean isPlaying = false;

    private Clip currentClip = null;
    private final Minecraft mc = Minecraft.getInstance();

    public MusicPlayer() {}

    // ============================================================
    //  曲目加载
    // ============================================================

    private void ensureScanned() {
        if (scanned) return;
        // JUKEBOX_SONG 是随存档同步的动态注册表，进入世界前读不到，
        // 此时直接返回且不置 scanned，避免把空列表永久缓存
        if (mc.level == null) return;
        scanned = true;

        // ---- 原版唱片：1.21 起唱片不再是 RecordItem 类，
        //      改为数据驱动的 JukeboxSong 注册表（原版 19 首） ----
        mc.level.registryAccess().registry(Registries.JUKEBOX_SONG).ifPresent(reg -> {
            List<MusicTrack> vanilla = new ArrayList<>();
            for (var entry : reg.entrySet()) {
                JukeboxSong song = entry.getValue();
                String name = song.description().getString();
                String soundId = song.soundEvent().unwrapKey()
                        .map(k -> k.location().toString()).orElse("");
                if (!name.isEmpty() && !soundId.isEmpty()) {
                    vanilla.add(new MusicTrack(name, soundId, false));
                }
            }
            vanilla.sort(Comparator.comparing(MusicTrack::name));
            tracks.addAll(vanilla);
        });

        // ---- 自定义 WAV (config/mcphone/music/) ----
        Path musicDir = Path.of("config/mcphone/music");
        if (Files.isDirectory(musicDir)) {
            try (var stream = Files.list(musicDir)) {
                stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".wav"))
                      .sorted().forEach(p -> {
                    String fn = p.getFileName().toString();
                    String displayName = fn.substring(0, fn.length() - 4);
                    tracks.add(new MusicTrack(displayName, p.toAbsolutePath().toString(), true));
                });
            } catch (Exception e) {
                LOGGER.warn("扫描自定义音乐目录失败: {}", e.getMessage());
            }
        } else {
            try { Files.createDirectories(musicDir); } catch (Exception ignored) {}
        }
    }

    // ============================================================
    //  渲染
    // ============================================================

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        ensureScanned();

        int x = phoneLeft + PAD;
        int y = phoneTop + statusH + 4;
        int w = screenW - PAD * 2;
        int bottom = phoneTop + screenH - navH;

        // 标题
        g.drawString(font, "音乐播放器", x, y, PhoneTheme.FONT_COLOR_TITLE, true);
        y += font.lineHeight + 4;

        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 4;

        if (tracks.isEmpty()) {
            g.drawString(font, "暂无曲目", x, y, PhoneTheme.FONT_COLOR_SUBTLE, false);
            y += font.lineHeight + 2;
            g.drawString(font, "放入WAV到 config/mcphone/music/", x, y, PhoneTheme.FONT_COLOR_DIM, false);
            hoveredIdx = -1;
            return;
        }

        int visibleRows = (bottom - y) / (font.lineHeight + 4);
        int maxScroll = Math.max(0, tracks.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        hoveredIdx = -1;
        for (int i = scrollOffset; i < Math.min(tracks.size(), scrollOffset + visibleRows + 1); i++) {
            if (y + font.lineHeight > bottom) break;

            MusicTrack t = tracks.get(i);
            int rowH = font.lineHeight + 2;
            boolean hovered = GuiUtil.hit(mouseX, mouseY, x, y, w, rowH);

            if (hovered) {
                hoveredIdx = i;
                g.fill(x, y, x + w, y + rowH, PhoneTheme.COLOR_ROW_HOVER);
            }
            if (i == playingIdx && isPlaying) {
                g.fill(x, y, x + w, y + rowH, PhoneTheme.COLOR_ROW_ACTIVE);
            }

            String label = t.isCustom() ? "♪ " : "♫ ";
            if (i == playingIdx && isPlaying) label = "▶ ";
            String full = label + t.name();
            if (font.width(full) > w - 4) {
                full = font.plainSubstrByWidth(full, w - 8) + "…";
            }
            g.drawString(font, full, x + 2, y + 1, PhoneTheme.FONT_COLOR_BODY, false);

            y += font.lineHeight + 4;
        }
    }

    // ============================================================
    //  鼠标
    // ============================================================

    public boolean mouseClicked(double mx, double my, int button) {
        if (hoveredIdx < 0 || hoveredIdx >= tracks.size()) return false;

        if (hoveredIdx == playingIdx && isPlaying) {
            stop();
            return true;
        }

        MusicTrack t = tracks.get(hoveredIdx);
        if (t.isCustom()) {
            playCustomAsync(t.soundId());
        } else {
            playVanilla(t.soundId());
        }
        playingIdx = hoveredIdx;
        isPlaying = true;
        return true;
    }

    // ============================================================
    //  播放
    // ============================================================

    private void playVanilla(String soundId) {
        ResourceLocation loc = ResourceLocation.parse(soundId);
        var event = BuiltInRegistries.SOUND_EVENT.get(loc);
        if (event != null && mc.player != null) {
            mc.player.playNotifySound(event, SoundSource.RECORDS, 1.0f, 1.0f);
        }
    }

    private void playCustomAsync(String filePath) {
        new Thread(() -> {
            try {
                AudioInputStream in = AudioSystem.getAudioInputStream(new java.io.File(filePath));
                AudioFormat fmt = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        in.getFormat().getSampleRate(), 16,
                        in.getFormat().getChannels(),
                        in.getFormat().getChannels() * 2,
                        in.getFormat().getSampleRate(), false);
                AudioInputStream dec = AudioSystem.getAudioInputStream(fmt, in);

                DataLine.Info info = new DataLine.Info(Clip.class, fmt);
                Clip clip = (Clip) AudioSystem.getLine(info);
                clip.open(dec);
                currentClip = clip;
                clip.start();

                clip.addLineListener(e -> {
                    if (e.getType() == LineEvent.Type.STOP) {
                        clip.close();
                        if (currentClip == clip) { currentClip = null; isPlaying = false; playingIdx = -1; }
                    }
                });
                dec.close(); in.close();
            } catch (Exception e) {
                LOGGER.warn("播放失败: {} - {}", filePath, e.getMessage());
                isPlaying = false; playingIdx = -1;
            }
        }, "mcphone-music").start();
    }

    private void stop() {
        if (currentClip != null) { currentClip.stop(); currentClip.close(); currentClip = null; }
        mc.getSoundManager().stop(null, SoundSource.RECORDS);
        playingIdx = -1; isPlaying = false;
    }

}
