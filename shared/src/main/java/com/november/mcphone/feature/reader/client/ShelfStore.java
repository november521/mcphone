package com.november.mcphone.feature.reader.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.november.mcphone.MCphone;
import com.november.mcphone.feature.reader.BookRef;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 书架 —— 玩家自己收下的那些书，落盘记着。
 *
 * 与"书城"的分工
 *
 * 书城是整合包里【有】的全部书，由各书源扫出来；书架是玩家【要】的那几本。
 * 几百个模组的整合包里书城有几十本，而一个人常翻的通常不超过五六本——书架
 * 存在的全部理由就是把那五六本从几十本里摘出来。
 *
 * 为什么存全局一份，而不是按存档分
 *
 * App 的安装状态是按存档存的（见 PhoneScreenRegistry），书架刻意不学它。
 * 因为书不是存档里的东西：它来自客户端装的模组，同一个整合包连到哪个服务器
 * 都是那几本。按存档分的结果是"同一个包、换个服务器，书架就空了"，而玩家
 * 想的是"我的书架跟着我走"。
 *
 * 代价是换整合包之后，架上会有一批当前认不出的书。那由 {@link #shelved} 顺手
 * 解决：它只返回当前书城里还在的书，认不出的既不显示也【不删除】——回到原来
 * 那个包，它们还在架上。这也是不做"清理不存在的条目"的原因，删了就回不来了。
 *
 * 记的是 id 不是书本身
 *
 * 存的是 (书源, 书 id) 这一对，不存书名与图标——那些每次都能从书源现拿，
 * 而且会随语言与模组更新变化。落盘存一份等于给自己留一份会过期的副本。
 *
 * 只在客户端线程上读写（界面绘制与点击都在那条线程），不加锁。
 */
public final class ShelfStore {

    private ShelfStore() {}

    /** 与音乐目录、壁纸目录同一个爹：config/mcphone/<功能>/ */
    private static final Path FILE = Path.of("config/mcphone/reader/shelf.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 架上的书，按【放上去的顺序】。null 表示还没从磁盘读过 */
    private static LinkedHashSet<Key> shelf;

    /**
     * 改了几次。
     *
     * 界面每帧都要问"书架上是哪几本"，答案只在玩家收藏/取消时才变。界面拿这个
     * 数当缓存的凭据：数没变就不必重新拼一遍表。不用"上次改的时间"是因为同一帧
     * 内连续两次改动的时间戳可能相同。
     */
    private static int revision;

    /** 一本书在架上的身份。书源 + 书 id，两者都要——将来不同书源可能有同名的书 */
    private record Key(String source, String id) {}

    private static Key keyOf(BookRef book) {
        return new Key(book.sourceId(), book.bookId().toString());
    }

    /** 磁盘上那份的形状。字段名就是 json 里的键，改名等于作废玩家已有的书架 */
    private static final class State {
        List<Entry> books;
    }

    private static final class Entry {
        String source;
        String id;
    }

    /** 改了几次，界面用它判断缓存还作不作数 */
    public static int revision() {
        return revision;
    }

    public static boolean contains(BookRef book) {
        return load().contains(keyOf(book));
    }

    /**
     * 收藏 / 取消收藏，并立刻落盘。
     *
     * 立刻写而不是等关机再写：玩家点一下 ☆ 就以为记住了，而游戏崩溃、强退、
     * 拔电源都不给我们"关机时再写"的机会。一次写几十个 id，代价可以忽略。
     *
     * @return 之后这本书在不在架上
     */
    public static boolean toggle(BookRef book) {
        LinkedHashSet<Key> books = load();
        Key key = keyOf(book);

        boolean nowShelved;
        if (books.remove(key)) {
            nowShelved = false;
        } else {
            books.add(key);          // 加在末尾：架上的顺序就是收藏的先后
            nowShelved = true;
        }

        revision++;
        save();
        return nowShelved;
    }

    /**
     * 架上那些书，按收藏顺序，且只包含【当前书城里还在】的。
     *
     * 认不出的条目原样留在磁盘上，理由见类注释。
     *
     * @param all 当前书城的全部书
     */
    public static List<BookRef> shelved(List<BookRef> all) {
        LinkedHashSet<Key> books = load();
        if (books.isEmpty() || all.isEmpty()) return List.of();

        // 先给书城建一张索引再按收藏顺序取，而不是两层循环：几十本 × 几本虽然
        // 也不慢，但这段每次收藏之后都要重跑一遍，写成 O(n+m) 不多花力气
        Map<Key, BookRef> byKey = new HashMap<>();
        for (BookRef book : all) byKey.putIfAbsent(keyOf(book), book);

        List<BookRef> out = new ArrayList<>(books.size());
        for (Key key : books) {
            BookRef book = byKey.get(key);
            if (book != null) out.add(book);
        }
        return List.copyOf(out);
    }

    /** 读一次存着。读不出来就当空书架——绝不因为一个坏文件让整个 App 打不开 */
    private static LinkedHashSet<Key> load() {
        LinkedHashSet<Key> cached = shelf;
        if (cached != null) return cached;

        LinkedHashSet<Key> out = new LinkedHashSet<>();
        if (Files.isRegularFile(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                State state = GSON.fromJson(r, State.class);
                if (state != null && state.books != null) {
                    for (Entry e : state.books) {
                        if (e == null || e.source == null || e.id == null) continue;
                        out.add(new Key(e.source, e.id));
                    }
                }
            } catch (Exception e) {
                MCphone.LOGGER.warn("[MCphone] 读取书架 {} 失败，这次按空书架处理：{}",
                        FILE, e.toString());
            }
        }

        shelf = out;
        return out;
    }

    private static void save() {
        State state = new State();
        state.books = new ArrayList<>();
        for (Key key : load()) {
            Entry e = new Entry();
            e.source = key.source();
            e.id = key.id();
            state.books.add(e);
        }

        try {
            Path parent = FILE.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(state, w);
            }
        } catch (IOException e) {
            // 只警告不抛：写不进去最多是这次收藏没记住，不该把界面带崩
            MCphone.LOGGER.warn("[MCphone] 写入书架 {} 失败：{}", FILE, e.toString());
        }
    }
}
