package com.november.mcphone.feature.gallery.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * 相册照片库 —— 扫描 <游戏目录>/screenshots/，按需提供缩略图。
 *
 * 目录里既有相机 App 拍的照片，也有玩家按 F2 随手截的图，两者都是
 * 原版 Screenshot.grab 写出来的，格式与命名完全一致，相册不作区分。
 *
 * 为什么不像 WallpaperStore 那样一次性全部加载
 *
 * 壁纸目录通常只有几张图，扫描时全读进来无所谓。截图目录则可能积攒
 * 成百上千张，而且每张都是全屏分辨率：一张 1920×1080 上传成贴图就是
 * 1920*1080*4 ≈ 8MB 显存，几十张就能把显存吃光，全量加载必然崩。
 *
 * 所以这里分三层处理：
 *
 *   1. 扫描只读元数据（路径、文件名、修改时间），不碰像素。
 *      两千张照片的扫描也只是一次目录列举。
 *   2. 缩略图按需加载：只有正在显示的那一页会去请求贴图。
 *   3. 缓存有上限（{@link #maxCached}），超出后按最久未使用逐出，
 *      并 release 掉贴图归还显存。翻页时旧页自然被挤出去。
 *
 * 另外上传前会把图缩到长边 {@link #THUMB_MAX_SIDE} 像素，一张缩略图
 * 只剩几十 KB，缓存满载也不到 1MB 显存。
 *
 * 线程
 *
 * 读盘与缩放在后台线程（IO + 大图缩放，放主线程会卡帧），
 * 只有最后的贴图上传回到主线程——GL 调用必须在渲染线程。
 *
 * 除后台任务只读的 Path 外，本类所有字段都仅在渲染线程访问
 * （thumbnail() 由渲染调用，注册回调经 mc.execute 也回到渲染线程），
 * 因此无需加锁。改动本类时务必守住这条约定。
 */
public final class PhotoLibrary {

    private static final Logger LOGGER = LoggerFactory.getLogger("mcphone/PhotoLibrary");

    /** 截图目录名，与原版 Screenshot.grab 写入的位置一致 */
    private static final String SCREENSHOT_DIR = "screenshots";

    /** 缩略图长边上限（像素）。手机屏幕只有 120 宽，96 足够清晰。 */
    private static final int THUMB_MAX_SIDE = 96;

    /** 缩略图贴图缓存下限。够装下一页还有余量。 */
    private static final int MIN_CACHED = 24;

    /**
     * 缩略图贴图缓存上限。
     *
     * 必须始终大于"一页的张数"，否则同一页里先加载的会被后加载的挤掉，
     * 下一帧又重新加载，陷入加载—逐出的死循环，画面持续闪烁。
     * 每页张数由界面按手机屏幕高度算出，不是定值，
     * 因此由界面调 {@link #ensureCacheFor} 把上限顶上去。
     */
    private static int maxCached = MIN_CACHED;

    /**
     * 声明"一页会同时显示多少张"，据此抬高缓存上限。
     * 只增不减：手机屏幕尺寸在一次游戏里不会反复变，缩回去没有意义。
     */
    public static void ensureCacheFor(int perPage) {
        // 多留一行的余量，翻页时上一页的尾巴还能命中
        int want = Math.max(MIN_CACHED, perPage + perPage / 3 + 1);
        if (want > maxCached) maxCached = want;
    }

    /** 同时在飞的加载数上限，防止快速翻页时打出 IO 风暴 */
    private static final int MAX_IN_FLIGHT = 4;

    private PhotoLibrary() {}

    //  数据

    /**
     * 一张照片的元数据。不含像素——像素按需加载，见 {@link #thumbnail}。
     *
     * @param path         文件绝对路径
     * @param fileName     文件名（含 .png 后缀）
     * @param lastModified 修改时间戳，用于排序与缓存键
     */
    public record Photo(Path path, String fileName, long lastModified) {

        /** 缓存键带上修改时间：同名文件被覆盖后键会变，不会读到旧贴图 */
        String cacheKey() { return fileName + ":" + lastModified; }
    }

    /**
     * 一张已就绪的缩略图贴图。
     *
     * 必须带上宽高：把缩略图等比塞进格子要用 GuiGraphics 的 11 参 blit，
     * 而那个重载需要纹理的真实尺寸才能算出源区域。缩略图是等比缩小的，
     * 宽高比各不相同（截图有 16:9 也有窗口化的怪比例），不能写死。
     */
    public record Thumb(ResourceLocation texture, int width, int height) {}

    /** 已扫描到的照片，新的在前 */
    private static final List<Photo> PHOTOS = new ArrayList<>();

    private static boolean scanned = false;

    // ---- 缩略图缓存 ----

    /**
     * 访问序 LinkedHashMap：每次 thumbnail() 命中都会把该项移到最新，
     * 于是"当前页"始终是热的，被逐出的必然是翻过去很久的旧页。
     */
    private static final Map<String, Thumb> THUMBNAILS =
            new LinkedHashMap<>(MIN_CACHED + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Thumb> eldest) {
                    if (size() <= maxCached) return false;
                    // 逐出的同时归还显存，否则贴图会一直留在 TextureManager 里
                    Minecraft.getInstance().getTextureManager().release(eldest.getValue().texture());
                    return true;
                }
            };

    /** 正在加载中的缓存键，避免同一张图被重复提交 */
    private static final Set<String> LOADING = new HashSet<>();

    /** 加载失败的缓存键，避免每帧重试同一个坏文件 */
    private static final Set<String> FAILED = new HashSet<>();

    /** 贴图 ResourceLocation 的自增序号，保证路径唯一且字符合法 */
    private static int textureSeq = 0;

    /**
     * 缓存世代，每次 releaseAll 递增。
     *
     * 后台加载完成时会比对世代：玩家退出相册后仍在飞的那几张加载，
     * 回来时世代已经变了，直接丢弃——否则它们会在刚清空的缓存里
     * 重新注册出几张没人回收的贴图。
     */
    private static int generation = 0;

    //  扫描

    /** 首次访问时自动扫描；要强制重扫用 {@link #refresh()} */
    public static List<Photo> getPhotos() {
        if (!scanned) refresh();
        return Collections.unmodifiableList(PHOTOS);
    }

    public static int count() { return getPhotos().size(); }

    public static Photo get(int index) {
        List<Photo> list = getPhotos();
        return (index >= 0 && index < list.size()) ? list.get(index) : null;
    }

    /**
     * 重新扫描截图目录。进入相册界面时调用，这样刚拍的照片立刻可见。
     *
     * 只读元数据，不加载像素，因此即便目录里有上千张也很快。
     */
    public static void refresh() {
        scanned = true;
        PHOTOS.clear();

        Path dir = screenshotDir();
        if (!Files.isDirectory(dir)) {
            // 一张都没拍过时目录尚不存在，属正常情况，不必创建：
            // 原版截图时会自己建
            return;
        }

        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                  .filter(Files::isRegularFile)
                  .forEach(p -> {
                      long mtime;
                      try {
                          mtime = Files.getLastModifiedTime(p).toMillis();
                      } catch (IOException e) {
                          mtime = 0L;
                      }
                      PHOTOS.add(new Photo(p, p.getFileName().toString(), mtime));
                  });
        } catch (IOException e) {
            LOGGER.warn("扫描截图目录失败: {}", e.getMessage());
        }

        // 新的排在前面；时间相同时按文件名倒序，保证顺序稳定不会每次扫描抖动
        PHOTOS.sort(Comparator.comparingLong(Photo::lastModified).reversed()
                .thenComparing(Comparator.comparing(Photo::fileName).reversed()));
    }

    private static Path screenshotDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(SCREENSHOT_DIR);
    }

    //  缩略图

    /**
     * 取一张照片的缩略图贴图。
     *
     * 未就绪时返回 null 并在后台发起加载——调用方应画一个占位格子，
     * 加载完成后的某一帧自然就拿到贴图了。每帧调用是安全的：
     * 加载中与失败的都有集合挡着，不会重复提交。
     */
    public static Thumb thumbnail(Photo photo) {
        if (photo == null) return null;

        String key = photo.cacheKey();
        Thumb cached = THUMBNAILS.get(key);   // 命中即刷新 LRU 位置
        if (cached != null) return cached;

        if (FAILED.contains(key) || LOADING.contains(key)) return null;
        // 在飞的太多就先不提交，下一帧还会再来问
        if (LOADING.size() >= MAX_IN_FLIGHT) return null;

        LOADING.add(key);
        load(photo, key);
        return null;
    }

    private static void load(Photo photo, String key) {
        final int gen = generation;

        submit(photo.path(), THUMB_MAX_SIDE, image -> {
            LOADING.remove(key);

            if (image == null) {
                // 失败也要认世代：清过场后旧的坏文件记录不该继续压着
                if (gen == generation) FAILED.add(key);
                return;
            }
            // 期间玩家已退出相册（releaseAll 递增了世代），或这张已被
            // 别的路径装好了——两种情况都直接丢弃，别再往缓存里塞
            if (gen != generation || THUMBNAILS.containsKey(key)) {
                image.close();
                return;
            }

            THUMBNAILS.put(key, upload(image, "photo_thumb_"));
        });
    }

    /**
     * 后台读盘缩放，完成后回到渲染线程交给 onReady（失败时传 null）。
     * 缩略图与大图预览共用这条路径。
     */
    private static void submit(Path path, int maxSide, java.util.function.Consumer<NativeImage> onReady) {
        Util.backgroundExecutor().execute(() -> {
            NativeImage image = readAndScale(path, maxSide);
            // 回到渲染线程：贴图上传是 GL 调用
            Minecraft.getInstance().execute(() -> onReady.accept(image));
        });
    }

    /** 把已就绪的 NativeImage 注册成贴图。必须在渲染线程调用。 */
    private static Thumb upload(NativeImage image, String namePrefix) {
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                "mcphone", namePrefix + (textureSeq++));
        // 尺寸要在 register 之前取：DynamicTexture 接管 NativeImage 后
        // 不该再碰它
        int w = image.getWidth();
        int h = image.getHeight();
        Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(image));
        return new Thumb(loc, w, h);
    }

    //  大图预览（单张查看用）

    /**
     * 预览图长边上限。
     *
     * 缩略图只有 96px，放大到满屏会糊。手机屏幕 120×200 GUI 像素，
     * GUI 缩放最高 4 倍即 480×800 实际像素，512 足够清晰又不至于
     * 把一张 4K 截图整个搬进显存。
     */
    private static final int PREVIEW_MAX_SIDE = 512;

    /** 当前预览的照片缓存键，null 表示没有预览 */
    private static String previewKey = null;

    /** 当前预览贴图，加载完成前为 null */
    private static Thumb preview = null;

    /**
     * 取一张照片的大图预览。同一时刻只保留一张——
     * 大图比缩略图重得多，缓存多张没有意义。
     *
     * 未就绪时返回 null，调用方可以先拿缩略图放大顶着，
     * 这样翻看时不会出现空白。
     */
    public static Thumb preview(Photo photo) {
        if (photo == null) return null;

        String key = photo.cacheKey();
        if (key.equals(previewKey)) return preview;   // 命中；加载中时 preview 仍为 null

        // 换了一张：立刻释放上一张，别让两张大图同时占着显存
        releasePreview();
        previewKey = key;

        final int gen = generation;
        submit(photo.path(), PREVIEW_MAX_SIDE, image -> {
            if (image == null) return;
            // 期间玩家已翻到别的照片或退出了相册，这张白读了，丢弃
            if (gen != generation || !key.equals(previewKey)) {
                image.close();
                return;
            }
            preview = upload(image, "photo_view_");
        });
        return null;
    }

    /** 释放预览贴图。退出单张查看、或切换到另一张时调用。 */
    public static void releasePreview() {
        if (preview != null) {
            Minecraft.getInstance().getTextureManager().release(preview.texture());
            preview = null;
        }
        // 键一并清掉：在飞的那次加载回来时会发现键对不上，自行丢弃
        previewKey = null;
    }

    //  删除

    /**
     * 从磁盘删除一张照片，随后重扫目录。
     *
     * 不可撤销——调用方应当先向玩家确认。
     *
     * @return 是否删除成功
     */
    public static boolean delete(Photo photo) {
        if (photo == null) return false;
        try {
            Files.deleteIfExists(photo.path());
        } catch (IOException e) {
            LOGGER.warn("删除照片失败: {} - {}", photo.fileName(), e.getMessage());
            return false;
        }

        // 连带回收这张的贴图：文件都没了，留着缓存纯占显存
        Thumb stale = THUMBNAILS.remove(photo.cacheKey());
        if (stale != null) Minecraft.getInstance().getTextureManager().release(stale.texture());
        if (photo.cacheKey().equals(previewKey)) releasePreview();

        refresh();
        return true;
    }

    /**
     * 读盘 → 缩小 → 转成 NativeImage。全程在后台线程，失败返回 null。
     */
    private static NativeImage readAndScale(Path path, int maxSide) {
        try (InputStream in = Files.newInputStream(path)) {
            BufferedImage src = ImageIO.read(in);
            if (src == null) {
                LOGGER.warn("无法读取照片: {}", path.getFileName());
                return null;
            }

            BufferedImage thumb = scaleDown(src, maxSide);
            int w = thumb.getWidth();
            int h = thumb.getHeight();

            NativeImage out = new NativeImage(w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = thumb.getRGB(x, y);
                    int a = (argb >> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    // ARGB → ABGR（NativeImage 的内部字节序）
                    out.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            return out;

        } catch (IOException e) {
            LOGGER.warn("加载照片失败: {} - {}", path.getFileName(), e.getMessage());
            return null;
        } catch (OutOfMemoryError e) {
            // 超大截图（如 4K 全景）可能撑爆堆，吞掉当作加载失败，
            // 总比整个游戏崩掉强
            LOGGER.warn("照片过大，内存不足: {}", path.getFileName());
            return null;
        }
    }

    /**
     * 等比缩小到长边不超过 maxSide。
     *
     * 逐次减半而不是一步到位：从 1920 直接 bilinear 缩到 96，每个目标像素
     * 只采样了源图 2×2 的范围，等于把 99% 的像素直接扔掉，结果全是噪点。
     * 每次只缩一半则相邻两级的 2×2 采样恰好覆盖全部像素，等效于逐级均值，
     * 缩略图就干净了。
     */
    private static BufferedImage scaleDown(BufferedImage src, int maxSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxSide && h <= maxSide) return src;

        // 目标尺寸：等比缩放，且至少 1 像素
        float ratio = (float) maxSide / Math.max(w, h);
        int targetW = Math.max(1, Math.round(w * ratio));
        int targetH = Math.max(1, Math.round(h * ratio));

        BufferedImage cur = src;
        int curW = w, curH = h;

        while (curW > targetW * 2 && curH > targetH * 2) {
            curW = Math.max(targetW, curW / 2);
            curH = Math.max(targetH, curH / 2);
            cur = redraw(cur, curW, curH);
        }
        return redraw(cur, targetW, targetH);
    }

    private static BufferedImage redraw(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = dst.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g2.drawImage(src, 0, 0, w, h, null);
        g2.dispose();
        return dst;
    }

    //  释放

    /**
     * 释放全部缩略图贴图。退出相册界面时调用——照片贴图对手机的其他
     * 界面毫无用处，留着白占显存。
     *
     * 正在加载中的不作处理：世代一变，它们完成时会自行丢弃。
     */
    public static void releaseAll() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (Thumb t : THUMBNAILS.values()) tm.release(t.texture());
        THUMBNAILS.clear();
        FAILED.clear();
        releasePreview();
        generation++;
    }
}
