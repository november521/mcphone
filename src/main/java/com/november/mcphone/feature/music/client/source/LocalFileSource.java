package com.november.mcphone.feature.music.client.source;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.music.Track;
import com.november.mcphone.feature.music.client.playback.AudioDecoders;
import com.november.mcphone.feature.music.client.playback.UnplayableException;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地文件音源 —— {@code config/mcphone/music/} 下的音乐。
 *
 * ================================================================
 * 为什么是 config 而不是存档目录
 * ================================================================
 *
 * 这是玩家自己的音乐，不属于任何一个世界。放进存档的话，换个存档就得
 * 再拷一遍，删存档还会把人家的歌一起删掉。
 *
 * ================================================================
 * 目录是可以随时改的
 * ================================================================
 *
 * 旧版本只在第一次打开 App 时扫一遍，之后新放进去的文件要重启游戏才认
 * ——而"往文件夹里丢首歌然后马上想听"恰恰是最常见的用法。现在每次打开
 * App 都重扫一次，玩家也能手动点刷新。
 *
 * 扫描本身很便宜（列一个目录、比一次扩展名），不必做什么增量或监听。
 */
public final class LocalFileSource implements MusicSource {

    /** 音源标识。会进播放记录，别改 */
    public static final String ID = "local";

    /** 玩家放歌的地方。相对游戏根目录，与项目里其他 config 路径一致 */
    public static final Path MUSIC_DIR = Path.of("config", "mcphone", "music");

    private List<Track> cached = List.of();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayNameKey() {
        return "mcphone.music.source.local";
    }

    @Override
    public List<Track> list() {
        return cached;
    }

    @Override
    public void refresh() {
        if (!Files.isDirectory(MUSIC_DIR)) {
            // 目录不在就建一个：玩家照着提示去放歌时，那个文件夹得已经存在，
            // 否则他还得自己一层层建出来
            try {
                Files.createDirectories(MUSIC_DIR);
            } catch (IOException e) {
                MCphone.LOGGER.warn("[MCphone] 建不出音乐目录 {}: {}", MUSIC_DIR, e.toString());
            }
            cached = List.of();
            return;
        }

        List<Track> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(MUSIC_DIR)) {
            files.filter(Files::isRegularFile)
                 // 只收认得的格式：目录里常混着封面图、歌词、说明文件，
                 // 把它们列出来只会让玩家点到一个放不响的东西
                 .filter(AudioDecoders::isSupported)
                 .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                 .forEach(p -> found.add(toTrack(p)));
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 扫描音乐目录失败: {}", e.toString());
        }

        cached = List.copyOf(found);
    }

    @Override
    public AudioStream open(Track track) throws IOException {
        Path file = MUSIC_DIR.resolve(track.id());

        // 校验一次：track.id() 是文件名，而它一路从缓存传过来，中间文件
        // 可能已经被删了。不查的话 open 会抛一个语焉不详的 NoSuchFileException
        if (!Files.isRegularFile(file)) {
            throw new UnplayableException(
                    Component.translatable("mcphone.music.problem.missing"),
                    "文件已经不在了：" + file);
        }
        return AudioDecoders.open(file);
    }

    /**
     * 文件名去掉扩展名当显示名。
     *
     * 不去读 ID3/Vorbis 标签：那要为每种格式再写一份标签解析，而玩家的
     * 文件名通常已经是"歌手 - 歌名"。真需要标签，那是另一个功能。
     */
    private static Track toTrack(Path file) {
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String title = dot > 0 ? fileName.substring(0, dot) : fileName;

        return new Track(ID, fileName, title, Track.UNKNOWN_DURATION, Track.Kind.LOCAL);
    }
}
