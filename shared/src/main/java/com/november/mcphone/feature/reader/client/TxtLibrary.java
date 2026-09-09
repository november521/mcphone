package com.november.mcphone.feature.reader.client;

import com.november.mcphone.MCphone;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 玩家自己的书 —— 扫描 {@code config/mcphone/reader/books/}，里面的 txt 就是他的小说。
 *
 * <h2>为什么是一个目录，而不是游戏里的"导入"按钮</h2>
 *
 * 与表情、壁纸、音乐同一条路（见 {@code StickerLibrary} 的类注释）：小说是从别处下下来的，
 * 一次丢十本进目录，比在手机屏幕上一本本点快得多。而且这条路不需要弹系统文件选择器——
 * 那东西在 macOS 上与游戏抢主线程，是有名的崩溃源。
 *
 * <h2>为什么记的是文件名，不是路径</h2>
 *
 * 书架收藏与阅读进度都按<b>文件名</b>记。整个游戏目录被搬到别处（换电脑、改整合包路径）
 * 之后，绝对路径全废，而文件名还在——玩家的进度不该因为他把文件夹挪了个地方就没了。
 *
 * <h2>书 id 是文件名的哈希</h2>
 *
 * {@code BookRef} 的 id 是 {@code ResourceLocation}，只认小写字母数字与几个符号，而小说
 * 文件名里几乎必然有中文、空格、括号。所以 id 取文件名的哈希（{@link #idOf}）：稳定、
 * 合法、并且改文件名＝换一本书（那本来就该是两本，进度也不该串）。
 */
public final class TxtLibrary {

    private TxtLibrary() {}

    /** 与音乐目录、壁纸目录同一个爹：config/mcphone/<功能>/ */
    private static final Path DIR = Path.of("config/mcphone/reader/books");

    /**
     * 一本书最大读多少。
     *
     * 32 MB 的纯文本约合一千五百万字，比任何一部长篇都富余得多。设上限是因为整个文件要
     * 一次读进内存：玩家往目录里丢了一个几 GB 的东西（备份、日志、放错的文件），没有这
     * 一道就是当场 OOM 把游戏带走。
     */
    private static final long MAX_BYTES = 32L * 1024 * 1024;

    /** 认得的后缀。只收纯文本：epub、mobi 是压缩包，解它们是另一件事 */
    private static final String EXTENSION = ".txt";

    /** 扫出来的书，缓存着。null 表示要重扫 */
    private static List<Entry> cached;

    /**
     * 目录里的一本书。
     *
     * @param fileName 带后缀的文件名，收藏与进度都按它记
     * @param title    去掉后缀的名字，就是书名——玩家给文件起的名字就是他认得的书名
     * @param bytes    文件多大，列表上显示"多少 KB"用
     */
    public record Entry(String fileName, String title, long bytes) {

        public Path path() {
            return DIR.resolve(fileName);
        }

        /** 这本书在 BookRef 里的 id，见类注释 */
        public String id() {
            return idOf(fileName);
        }
    }

    /** 当前这些书。便宜，界面每帧问都行 */
    public static List<Entry> books() {
        List<Entry> books = cached;
        if (books != null) return books;

        refresh();
        return cached;
    }

    /**
     * 重扫目录。
     *
     * 顺手把目录建出来：玩家第一次点开阅读 App 时它就在那儿了，不必先问"我该把书放哪"。
     * 建不出来（只读的整合包目录）不是错，只是这次没有本地书。
     */
    public static void refresh() {
        List<Entry> out = new ArrayList<>();

        try {
            Files.createDirectories(DIR);
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 建不出小说目录 {}：{}", DIR, e.toString());
        }

        if (Files.isDirectory(DIR)) {
            try (Stream<Path> files = Files.list(DIR)) {
                files.filter(Files::isRegularFile)
                     .filter(TxtLibrary::isTxt)
                     .forEach(path -> out.add(entryOf(path)));
            } catch (IOException e) {
                MCphone.LOGGER.warn("[MCphone] 扫描小说目录 {} 失败：{}", DIR, e.toString());
            }
        }

        // 按书名排，而不是按文件系统给的顺序——那个顺序在不同平台上不一样，
        // 玩家会觉得"每次进来书的位置都在变"。书架页可以自己拖着排，那是另一回事
        out.sort(Comparator.comparing(entry -> entry.title().toLowerCase(Locale.ROOT)));
        cached = List.copyOf(out);
    }

    /** 按 BookRef 里的 id 找回是哪一本，找不到返回 null（文件被删了、改名了） */
    public static Entry byId(String id) {
        for (Entry entry : books()) {
            if (entry.id().equals(id)) return entry;
        }
        return null;
    }

    /**
     * 把一本书读进来并切好章。
     *
     * 这一下是<b>同步</b>的：几 MB 的解码加切章在毫秒级，而放到别的线程去就要处理"读到
     * 一半玩家关了手机"，代价比收益大。真放了一个超大文件进来时，上面那道 {@link #MAX_BYTES}
     * 会先把它挡住。
     *
     * @return 读不出来时返回 null，并且已经记过日志——调用方负责告诉玩家"这本打不开"
     */
    public static TxtBook load(Entry entry) {
        if (entry == null) return null;

        Path path = entry.path();
        try {
            long size = Files.size(path);
            if (size > MAX_BYTES) {
                MCphone.LOGGER.warn("[MCphone] {} 有 {} MB，超过 {} MB 的上限，不读",
                        entry.fileName(), size / 1024 / 1024, MAX_BYTES / 1024 / 1024);
                return null;
            }

            byte[] bytes = Files.readAllBytes(path);
            return TxtBook.of(entry, bytes);
        } catch (IOException | OutOfMemoryError e) {
            // 兜 OutOfMemoryError 而不只是 IOException：上限之内的文件仍可能在内存吃紧时
            // 读爆，那时候丢一本书总比把游戏带走强
            MCphone.LOGGER.error("[MCphone] 读小说 {} 失败", path, e);
            return null;
        }
    }

    /**
     * 小说目录本身，并且<b>保证它存在</b>——「打开文件夹」那个键要用。
     *
     * 目录不在就先建出来：交给系统的文件管理器打开一个不存在的路径，多半是弹一句"路径不存在"，
     * 而玩家点它的目的恰恰是"我还没有书，想放几本进去"。与相册那边同一条规矩。
     */
    public static Path directory() {
        try {
            Files.createDirectories(DIR);
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 建不出小说目录 {}：{}", DIR, e.toString());
        }
        return DIR;
    }

    /**
     * 把一个外部 txt 收进小说目录 —— 拖进游戏窗口那条路用（见 {@code PhoneScreen.onFilesDrop}）。
     *
     * 重名的加后缀而不是覆盖：玩家拖进来的多半是新下的一本，覆盖掉同名的那本等于把他
     * 读了一半的书连同进度一起换掉（进度是按文件名记的）。
     *
     * @return 收进来之后的文件名，失败返回 null（已经记过日志）
     */
    public static String importFrom(Path source) {
        if (source == null || !Files.isRegularFile(source) || !isTxt(source)) return null;

        try {
            if (Files.size(source) > MAX_BYTES) {
                MCphone.LOGGER.warn("[MCphone] {} 超过 {} MB 的上限，不收",
                        source.getFileName(), MAX_BYTES / 1024 / 1024);
                return null;
            }

            Files.createDirectories(DIR);

            String fileName = uniqueName(source.getFileName().toString(),
                    name -> Files.exists(DIR.resolve(name)));

            Files.copy(source, DIR.resolve(fileName));
            refresh();
            MCphone.LOGGER.info("[MCphone] 收下一本小说：{}", fileName);
            return fileName;
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 收 {} 失败：{}", source, e.toString());
            return null;
        }
    }

    /**
     * 文件名 → 稳定的 id。
     *
     * 用 SHA-256 的前 8 个字节：文件名里有中文、空格、括号，直接塞进 ResourceLocation
     * 会被拒；而哈希短、稳定、合法。碰撞概率对"一个目录里几十本书"来说不必考虑。
     */
    static String idOf(String fileName) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(fileName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            // SHA-256 是 JDK 必备的，走不到这儿；真走到了就退回一个还算稳定的数
            return Integer.toHexString(fileName.hashCode());
        }
    }

    /**
     * 重名时该叫什么：{@code 书.txt} → {@code 书 (2).txt} → {@code 书 (3).txt}。
     *
     * 单拎出来是因为这段"加后缀"的算术最容易差一位——后缀加到 {@code .txt} 后面
     * （{@code 书.txt (2)}）就不再是文本文件，扫描时直接看不见；而症状是"我拖进去的书
     * 不见了"，玩家不会想到是文件名的事。
     *
     * @param taken 这个名字被占了没有。传进来而不是直接查磁盘，是为了这段能单独验证
     */
    static String uniqueName(String fileName, java.util.function.Predicate<String> taken) {
        if (!taken.test(fileName)) return fileName;

        String base = fileName.toLowerCase(Locale.ROOT).endsWith(EXTENSION)
                ? fileName.substring(0, fileName.length() - EXTENSION.length())
                : fileName;

        for (int n = 2; n < 1000; n++) {
            String candidate = base + " (" + n + ")" + EXTENSION;
            if (!taken.test(candidate)) return candidate;
        }
        // 同一本拖进来一千次。到这一步就别再试了，覆盖比无限循环强
        return fileName;
    }

    private static boolean isTxt(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    private static Entry entryOf(Path path) {
        String fileName = path.getFileName().toString();
        String title = fileName.substring(0, fileName.length() - EXTENSION.length());

        long size = 0;
        try {
            size = Files.size(path);
        } catch (IOException ignored) {
            // 大小只用来显示，取不到就写 0，不值得为它放弃这本书
        }
        return new Entry(fileName, title.isBlank() ? fileName : title, size);
    }

    /** 猜出来的编码，给界面显示用（乱码时玩家至少知道我们按什么解的） */
    public static String charsetName(Charset charset) {
        return charset == null ? "?" : charset.name();
    }
}
