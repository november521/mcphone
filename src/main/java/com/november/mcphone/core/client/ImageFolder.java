package com.november.mcphone.core.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.november.mcphone.MCphone;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 一个"装着图的目录"：扫描它、按需出缩略图、缓存有上限。
 *
 * 相册（截图目录）与美西螈的表情（{@code config/mcphone/stickers/}）要的是同一件事，
 * 只有目录、认哪些后缀、缩略图多大这几处不同，所以做成实例而不是又一份静态类。
 *
 * 为什么不一次性全部加载
 *
 * 壁纸目录通常只有几张图，扫描时全读进来无所谓。截图目录则可能积攒成百上千张，而且每张都是
 * 全屏分辨率：一张 1920×1080 上传成贴图就是 1920*1080*4 ≈ 8MB 显存，几十张就能把显存吃光，
 * 全量加载必然崩。
 *
 * 所以分三层：
 *
 *   1. 扫描只读元数据（路径、文件名、修改时间），不碰像素。两千张也只是一次目录列举
 *   2. 缩略图按需加载：只有正在显示的那一页会去请求贴图
 *   3. 缓存有上限（{@link #maxCached}），超出后按最久未使用逐出，并 release 掉贴图归还显存
 *
 * 另外上传前会把图缩到长边 {@code thumbMaxSide} 像素，一张缩略图只剩几十 KB。
 *
 * 线程
 *
 * 读盘与缩放在后台线程（IO + 大图缩放，放主线程会卡帧），只有最后的贴图上传回到主线程——
 * GL 调用必须在渲染线程。
 *
 * 除后台任务只读的 Path 外，本类所有字段都仅在渲染线程访问（thumbnail() 由渲染调用，
 * 回调经 mc.execute 也回到渲染线程），因此无需加锁。改动本类时务必守住这条约定。
 */
public final class ImageFolder {

    /**
     * 目录里的一个文件。不含像素——像素按需加载，见 {@link #thumbnail}。
     *
     * @param path         文件绝对路径
     * @param fileName     文件名（含后缀）
     * @param lastModified 修改时间戳，用于排序与缓存键
     */
    public record Entry(Path path, String fileName, long lastModified) {

        /** 缓存键带上修改时间：同名文件被覆盖后键会变，不会读到旧贴图 */
        public String cacheKey() { return fileName + ":" + lastModified; }
    }

    /** 与原版截图的命名风格一致：年-月-日_时.分.秒 */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    /** 同时在飞的加载数上限，防止快速翻页时打出 IO 风暴 */
    private static final int MAX_IN_FLIGHT = 4;

    /** 目录现算现取：游戏目录、配置目录都要等 Minecraft 起来之后才有 */
    private final Supplier<Path> directory;

    private final Set<String> extensions;
    private final int thumbMaxSide;
    private final int minCached;
    private final String texturePrefix;

    private final List<Entry> entries = new ArrayList<>();
    private boolean scanned;

    /**
     * 访问序 LinkedHashMap：每次 thumbnail() 命中都会把该项移到最新，
     * 于是"当前页"始终是热的，被逐出的必然是翻过去很久的旧页。
     */
    private final Map<String, ImageCodec.Texture> thumbnails;

    /** 正在加载中的缓存键，避免同一张图被重复提交 */
    private final Set<String> loading = new HashSet<>();

    /** 加载失败的缓存键，避免每帧重试同一个坏文件 */
    private final Set<String> failed = new HashSet<>();

    /**
     * 缓存上限。必须始终大于"一页的张数"，否则同一页里先加载的会被后加载的挤掉，
     * 下一帧又重新加载，陷入加载—逐出的死循环，画面持续闪烁。每页张数由界面按屏幕高度算出，
     * 不是定值，因此由界面调 {@link #ensureCacheFor} 把上限顶上去。
     */
    private int maxCached;

    /**
     * 缓存世代，每次 {@link #releaseAll} 递增。
     *
     * 界面退出后仍在飞的那几张加载，回来时世代已经变了，直接丢弃——否则它们会在刚清空的
     * 缓存里重新注册出几张没人回收的贴图。
     */
    private int generation;

    public ImageFolder(Supplier<Path> directory, Set<String> extensions,
                       int thumbMaxSide, int minCached, String texturePrefix) {
        this.directory = directory;
        this.extensions = extensions;
        this.thumbMaxSide = thumbMaxSide;
        this.minCached = minCached;
        this.maxCached = minCached;
        this.texturePrefix = texturePrefix;
        this.thumbnails = new LinkedHashMap<>(minCached + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ImageCodec.Texture> eldest) {
                if (size() <= maxCached) return false;
                // 逐出的同时归还显存，否则贴图会一直留在 TextureManager 里
                ImageCodec.release(eldest.getValue());
                return true;
            }
        };
    }

    public Path directory() {
        return directory.get();
    }

    /**
     * 确保目录存在，返回它。
     *
     * 「打开文件夹」那个键要用：目录还不存在时交给系统的文件管理器，多半是弹一个"路径不存在"，
     * 而玩家点它的目的恰恰是"我还没有表情，想放几张进去"。
     */
    public Path ensureDirectory() {
        Path dir = directory.get();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 建目录失败 {}: {}", dir, e.getMessage());
        }
        return dir;
    }

    /**
     * 声明"一页会同时显示多少张"，据此抬高缓存上限。
     * 只增不减：屏幕尺寸在一次游戏里不会反复变，缩回去没有意义。
     */
    public void ensureCacheFor(int perPage) {
        // 多留一行的余量，翻页时上一页的尾巴还能命中
        int want = Math.max(minCached, perPage + perPage / 3 + 1);
        if (want > maxCached) maxCached = want;
    }

    //  扫描

    /** 首次访问时自动扫描；要强制重扫用 {@link #refresh()} */
    public List<Entry> entries() {
        if (!scanned) refresh();
        return Collections.unmodifiableList(entries);
    }

    public int count() { return entries().size(); }

    public Entry get(int index) {
        List<Entry> list = entries();
        return (index >= 0 && index < list.size()) ? list.get(index) : null;
    }

    /**
     * 重新扫描目录。进界面时调用，这样刚放进去的文件立刻可见。
     *
     * 只读元数据，不加载像素，因此即便目录里有上千张也很快。
     */
    public void refresh() {
        scanned = true;
        entries.clear();

        Path dir = directory.get();
        if (!Files.isDirectory(dir)) {
            // 一张都没有时目录可能还不存在，属正常情况，不在这里创建：
            // 真要写东西进去时（save / importFrom）再建
            return;
        }

        try (var stream = Files.list(dir)) {
            stream.filter(this::accepts)
                  .filter(Files::isRegularFile)
                  .forEach(p -> {
                      long mtime;
                      try {
                          mtime = Files.getLastModifiedTime(p).toMillis();
                      } catch (IOException e) {
                          mtime = 0L;
                      }
                      entries.add(new Entry(p, p.getFileName().toString(), mtime));
                  });
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 扫描目录失败 {}: {}", dir, e.getMessage());
        }

        // 新的排在前面；时间相同时按文件名倒序，保证顺序稳定不会每次扫描抖动
        entries.sort(Comparator.comparingLong(Entry::lastModified).reversed()
                .thenComparing(Comparator.comparing(Entry::fileName).reversed()));
    }

    private boolean accepts(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    //  缩略图

    /**
     * 取一张的缩略图贴图。
     *
     * 未就绪时返回 null 并在后台发起加载——调用方应画一个占位格子，加载完成后的某一帧
     * 自然就拿到贴图了。每帧调用是安全的：加载中与失败的都有集合挡着，不会重复提交。
     */
    public ImageCodec.Texture thumbnail(Entry entry) {
        if (entry == null) return null;

        String key = entry.cacheKey();
        ImageCodec.Texture cached = thumbnails.get(key);   // 命中即刷新 LRU 位置
        if (cached != null) return cached;

        if (failed.contains(key) || loading.contains(key)) return null;
        // 在飞的太多就先不提交，下一帧还会再来问
        if (loading.size() >= MAX_IN_FLIGHT) return null;

        loading.add(key);
        final int gen = generation;

        submit(entry.path(), thumbMaxSide, image -> {
            loading.remove(key);

            if (image == null) {
                // 失败也要认世代：清过场后旧的坏文件记录不该继续压着
                if (gen == generation) failed.add(key);
                return;
            }
            // 期间界面已退出（releaseAll 递增了世代），或这张已被别的路径装好了
            if (gen != generation || thumbnails.containsKey(key)) {
                image.close();
                return;
            }
            thumbnails.put(key, ImageCodec.upload(image, texturePrefix));
        });
        return null;
    }

    /**
     * 后台读盘缩放，完成后回到渲染线程交给 onReady（失败时传 null）。
     * 缩略图与调用方自己的大图预览共用这条路径。
     */
    public void submit(Path path, int maxSide, Consumer<NativeImage> onReady) {
        Util.backgroundExecutor().execute(() -> {
            NativeImage image = ImageCodec.readAndScale(path, maxSide);
            // 回到渲染线程：贴图上传是 GL 调用
            Minecraft.getInstance().execute(() -> onReady.accept(image));
        });
    }

    /** 当前世代。调用方自己缓存东西时用它判断"期间有没有清过场" */
    public int generation() { return generation; }

    //  写入

    /**
     * 把一段 PNG 字节写进这个目录，成功返回文件名。
     *
     * 文件名是 前缀 + 时间戳，与目录里原有的东西区分得开，也不会撞名。
     * 后台线程调用——这是一次写盘。写完不主动 refresh：界面每次打开都会重扫。
     */
    public String save(byte[] png, String namePrefix) {
        String name = namePrefix + TIMESTAMP.format(LocalDateTime.now()) + ".png";
        try {
            Path dir = directory.get();
            Files.createDirectories(dir);
            // CREATE_NEW：同一秒内存两张也不会把先存的那张覆盖掉
            Files.write(dir.resolve(name), png, StandardOpenOption.CREATE_NEW);
            return name;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            MCphone.LOGGER.warn("[MCphone] 保存失败：{} 已存在", name);
            return null;
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 保存失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 把一个外部文件复制进这个目录，成功返回文件名。
     *
     * 复制而不是记住原路径：原文件在别处，玩家随时可能挪走或删掉，而表情是要长期用的。
     * 重名时在名字后面挂一个序号，不覆盖已有的那张——玩家看到的是两张都在，
     * 而不是"我导入了一张，原来那张不见了"。
     */
    public String importFrom(Path source) {
        if (source == null || !accepts(source)) return null;

        try {
            Path dir = directory.get();
            Files.createDirectories(dir);

            String name = source.getFileName().toString();
            Path target = dir.resolve(name);
            for (int i = 2; Files.exists(target); i++) {
                int dot = name.lastIndexOf('.');
                name = (dot < 0 ? name : name.substring(0, dot)) + "-" + i
                        + (dot < 0 ? "" : name.substring(dot));
                target = dir.resolve(name);
            }

            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            return target.getFileName().toString();
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 导入失败 {}: {}", source, e.getMessage());
            return null;
        }
    }

    //  删除

    /**
     * 从磁盘删除一个文件，随后重扫目录。不可撤销——调用方应当先向玩家确认。
     *
     * @return 是否删除成功
     */
    public boolean delete(Entry entry) {
        if (entry == null) return false;
        try {
            Files.deleteIfExists(entry.path());
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 删除失败 {}: {}", entry.fileName(), e.getMessage());
            return false;
        }

        // 连带回收这张的贴图：文件都没了，留着缓存纯占显存
        ImageCodec.release(thumbnails.remove(entry.cacheKey()));
        refresh();
        return true;
    }

    /** 单独回收一张的贴图（调用方另有缓存时用得上） */
    public void release(String cacheKey) {
        ImageCodec.release(thumbnails.remove(cacheKey));
    }

    /**
     * 释放全部缩略图贴图。退出界面时调用——这些贴图对别的界面毫无用处，留着白占显存。
     * 正在加载中的不作处理：世代一变，它们完成时会自行丢弃。
     */
    public void releaseAll() {
        for (ImageCodec.Texture t : thumbnails.values()) ImageCodec.release(t);
        thumbnails.clear();
        failed.clear();
        generation++;
    }
}
