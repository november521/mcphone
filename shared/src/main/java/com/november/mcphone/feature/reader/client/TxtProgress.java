package com.november.mcphone.feature.reader.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.november.mcphone.MCphone;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 读到哪儿了 —— 每本书记一对"第几章、第几页"，落盘。
 *
 * <h2>为什么这件事非有不可</h2>
 *
 * 一部小说几百章。没有进度的话，玩家每次点开都从第一章开始，得自己记着"我看到第几章"
 * 再从目录里翻过去——那比不做这个功能还难受。这是本地阅读器的最低要求，不是锦上添花。
 *
 * <h2>按文件名记</h2>
 *
 * 与书架收藏同一个道理（见 {@code ShelfStore}）：书是玩家自己的东西，不属于任何存档，
 * 换服务器、换世界都该跟着走。记文件名而不是路径，整个游戏目录搬了家也还认得。
 *
 * <h2>页码为什么也记</h2>
 *
 * 只记章的话，每次回来都要从这一章的开头重看——一章两三千字，在这块屏幕上是七八页。
 * 页码是随屏幕宽度变的（改了界面大小、换成平板，同一章的页数就变了），所以它只是个
 * <b>提示</b>：翻回去时会夹进当前的页数范围里，最坏是落在附近，不会指到不存在的页。
 *
 * 每翻一页就写一次盘：一次几百字节，而"崩溃/强退/拔电源"从不给我们"退出时再写"的
 * 机会——这一条与书架收藏的取舍完全一样。
 *
 * <h2>书不在了也不删它的进度</h2>
 *
 * 文件被挪走、改了名，这份表里就多一条对不上的记录。<b>刻意不清</b>：与书架收藏同一个
 * 道理（见 {@code ShelfStore}），玩家多半只是把书临时收进了别的文件夹，删了就回不来——
 * 而留着的代价只是一行 json。
 */
public final class TxtProgress {

    private TxtProgress() {}

    private static final Path FILE = Path.of("config/mcphone/reader/progress.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 文件名 → 读到哪儿。null 表示还没从磁盘读过 */
    private static Map<String, Spot> progress;

    /** 读到第几章的第几页 */
    public record Spot(int chapter, int page) {

        public static final Spot START = new Spot(0, 0);
    }

    /** 磁盘上那份的形状。字段名就是 json 里的键，改名等于作废玩家已有的进度 */
    private static final class State {
        List<Entry> books;
    }

    private static final class Entry {
        String file;
        int chapter;
        int page;
    }

    /** 这本书读到哪儿了，没读过就是开头 */
    public static Spot get(String fileName) {
        if (fileName == null) return Spot.START;
        return load().getOrDefault(fileName, Spot.START);
    }

    /** 记下读到哪儿了。没变就不写盘——翻页很频繁，而多数翻页只改页码不改章 */
    public static void set(String fileName, int chapter, int page) {
        if (fileName == null) return;

        Spot spot = new Spot(Math.max(0, chapter), Math.max(0, page));
        Map<String, Spot> books = load();
        if (spot.equals(books.get(fileName))) return;

        books.put(fileName, spot);
        save();
    }

    /** 读一次存着。读不出来就当没有进度——绝不因为一个坏文件让书打不开 */
    private static Map<String, Spot> load() {
        Map<String, Spot> cached = progress;
        if (cached != null) return cached;

        Map<String, Spot> out = new HashMap<>();
        if (Files.isRegularFile(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                State state = GSON.fromJson(r, State.class);
                if (state != null && state.books != null) {
                    for (Entry e : state.books) {
                        if (e == null || e.file == null) continue;
                        out.put(e.file, new Spot(Math.max(0, e.chapter), Math.max(0, e.page)));
                    }
                }
            } catch (Exception e) {
                MCphone.LOGGER.warn("[MCphone] 读取阅读进度 {} 失败，这次从头开始：{}",
                        FILE, e.toString());
            }
        }

        progress = out;
        return out;
    }

    private static void save() {
        State state = new State();
        state.books = new ArrayList<>();
        for (Map.Entry<String, Spot> e : load().entrySet()) {
            Entry entry = new Entry();
            entry.file = e.getKey();
            entry.chapter = e.getValue().chapter();
            entry.page = e.getValue().page();
            state.books.add(entry);
        }

        try {
            Path parent = FILE.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(state, w);
            }
        } catch (IOException e) {
            // 只警告不抛：写不进去最多是这次的进度没记住，不该把界面带崩
            MCphone.LOGGER.warn("[MCphone] 写入阅读进度 {} 失败：{}", FILE, e.toString());
        }
    }
}
