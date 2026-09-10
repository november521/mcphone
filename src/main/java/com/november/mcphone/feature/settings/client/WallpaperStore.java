package com.november.mcphone.feature.settings.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 壁纸存储 —— 扫描 config/mcphone/wallpapers/ 目录，加载 PNG 为纹理。
 *
 * 支持任意尺寸 PNG，渲染时会等比例适配到手机屏幕。
 *
 * 玩家使用流程：
 * 1. 把任意尺寸的 PNG 图片放入 config/mcphone/wallpapers/
 * 2. 打开手机 → 设置 → 壁纸 → 选择壁纸
 */
public final class WallpaperStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("mcphone/WallpaperStore");

    private static final String WALLPAPER_DIR = "config/mcphone/wallpapers";

    /** 已加载的壁纸列表 */
    private static final List<WallpaperEntry> WALLPAPERS = new ArrayList<>();

    /**
     * 贴图键的序号，只增不减。
     *
     * 键原先由显示名清洗而来，于是 "我的 壁纸.png" 与 "我的_壁纸.png" 会算出
     * 同一个键，后加载的那张会把前一张顶掉——两张壁纸共用一张图，而且不报错。
     * 加个序号就不可能撞。
     */
    private static int textureSeq;

    private WallpaperStore() {}

    //  数据类 —— 记录图片原始宽高

    public record WallpaperEntry(
            String fileName,
            String displayName,
            ResourceLocation texture,
            int imageWidth,
            int imageHeight
    ) {}

    //  扫描 & 加载

    /** 客户端启动时扫一次，让第一次开机就有壁纸可选 */
    public static void scan() {
        refresh();
    }

    /** 启动扫描只排一次队；之后每 tick 一次布尔判断是回调的全部开销 */
    private static boolean initialScanQueued;
    private static boolean initialScanDone;

    /**
     * 启动扫描，推迟到第一个客户端 tick 再做。
     *
     * <h2>为什么不能在客户端入口点直接扫</h2>
     *
     * {@link #loadWallpaper} 每张图都要 new DynamicTexture —— 那是一步 GL 调用
     * （glGenTextures）。而 Fabric 的客户端入口点跑在 Minecraft 的构造函数里，
     * 游戏窗口此刻还没建，GL 上下文不存在：目录里只要有一张图，glGenTextures
     * 就原生崩溃（EXCEPTION_ACCESS_VIOLATION，连 MC 崩溃报告都没有，只有 hs_err）。
     * 目录空着时扫描什么都不加载，所以这个雷一直埋到玩家第一次往 wallpapers/
     * 放图才炸（2026-09-10 实测）。
     *
     * 第一个客户端 tick 时标题屏已经在渲染，GL 一定可用。
     */
    public static void scheduleInitialScan() {
        if (initialScanQueued) return;
        initialScanQueued = true;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (initialScanDone) return;
            initialScanDone = true;
            scan();
        });
    }

    /**
     * 壁纸目录，不存在就先建出来。「打开文件夹」那个键要用。
     *
     * 建不出来也照样把路径交出去：交给系统的文件管理器，它自己会说"这个路径不存在"，
     * 那比我们在手机屏幕上憋一句错误提示要清楚。
     */
    public static Path directory() {
        Path dir = Path.of(WALLPAPER_DIR);
        if (!Files.isDirectory(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                LOGGER.warn("无法创建壁纸目录: {}", e.getMessage());
            }
        }
        return dir;
    }

    /**
     * 重扫壁纸目录 —— 每次打开「更换壁纸」都调。
     *
     * 为什么必须能重扫
     *
     * 原先只在客户端启动时扫一遍，之后往目录里放的图要重启游戏才认。
     * 而"把图拷进 wallpapers 文件夹然后马上想换上"恰恰是这个功能唯一的
     * 用法——玩家不会为了换张壁纸重启一次游戏。
     *
     * 增量，不是推倒重来
     *
     * 加载一张壁纸要读文件、逐像素转格式、再传一张贴图上显卡，几百毫秒
     * 起步。每次打开界面把全部重来一遍，图一多就是肉眼可见的卡顿。
     *
     * 所以只做差集：新出现的加载，已经没了的释放掉贴图，剩下的原样留着。
     * 释放不能省——贴图是显存，只加不减的话，反复增删壁纸会一路涨上去。
     */
    public static void refresh() {
        Path dir = Path.of(WALLPAPER_DIR);
        if (!Files.isDirectory(dir)) {
            try {
                Files.createDirectories(dir);
                LOGGER.info("已创建壁纸目录: {}", dir.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.warn("无法创建壁纸目录: {}", e.getMessage());
            }
            dropAll();
            return;
        }

        List<String> onDisk = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                  .sorted()
                  .forEach(p -> onDisk.add(p.getFileName().toString()));
        } catch (IOException e) {
            LOGGER.warn("扫描壁纸目录失败: {}", e.getMessage());
            return;   // 读不到目录时保持现状，别把已经加载好的清空
        }

        // ---- 文件没了的：释放贴图再摘掉 ----
        WALLPAPERS.removeIf(entry -> {
            if (onDisk.contains(entry.fileName())) return false;
            Minecraft.getInstance().getTextureManager().release(entry.texture());
            LOGGER.debug("壁纸已移除: {}", entry.fileName());
            return true;
        });

        // ---- 新出现的：加载 ----
        for (String fileName : onDisk) {
            if (isLoaded(fileName)) continue;
            loadWallpaper(dir.resolve(fileName));
        }

        // 排序放在最后：新加载的都追加在末尾，不排的话新图永远排在最后，
        // 与文件名顺序对不上
        WALLPAPERS.sort(java.util.Comparator.comparing(WallpaperEntry::fileName));
    }

    private static boolean isLoaded(String fileName) {
        for (WallpaperEntry e : WALLPAPERS) {
            if (e.fileName().equals(fileName)) return true;
        }
        return false;
    }

    /** 目录整个没了的情况：贴图一并释放，否则那几张显存永远留着 */
    private static void dropAll() {
        for (WallpaperEntry e : WALLPAPERS) {
            Minecraft.getInstance().getTextureManager().release(e.texture());
        }
        WALLPAPERS.clear();
    }

    private static void loadWallpaper(Path path) {
        String fileName = path.getFileName().toString();
        String displayName = fileName.substring(0, fileName.length() - 4);

        try (InputStream in = Files.newInputStream(path)) {
            BufferedImage awtImage = ImageIO.read(in);
            if (awtImage == null) {
                LOGGER.warn("无法读取壁纸图片: {}", fileName);
                return;
            }

            int imgW = awtImage.getWidth();
            int imgH = awtImage.getHeight();

            // 转为 Minecraft NativeImage
            NativeImage nativeImage = new NativeImage(imgW, imgH, false);

            for (int y = 0; y < imgH; y++) {
                for (int x = 0; x < imgW; x++) {
                    int argb = awtImage.getRGB(x, y);
                    int a = (argb >> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    // ARGB → ABGR (Minecraft NativeImage 内部格式)
                    int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                    nativeImage.setPixelRGBA(x, y, abgr);
                }
            }

            // 注册为动态纹理 —— 使用图片原始尺寸。
            // 键里带一个只增不减的序号，理由见 textureSeq
            String texKey = "wp_" + (textureSeq++) + "_"
                    + displayName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
            ResourceLocation texLoc = ResourceLocation.fromNamespaceAndPath("mcphone", texKey);
            DynamicTexture dynTex = new DynamicTexture(nativeImage);
            Minecraft.getInstance().getTextureManager().register(texLoc, dynTex);

            WALLPAPERS.add(new WallpaperEntry(fileName, displayName, texLoc, imgW, imgH));
            LOGGER.debug("已加载壁纸: {} ({}×{})", fileName, imgW, imgH);

        } catch (IOException e) {
            LOGGER.warn("加载壁纸失败: {} - {}", fileName, e.getMessage());
        }
    }

    //  导入

    /**
     * 把玩家选中的一张图复制进壁纸目录，返回最终文件名；失败返回 null。
     *
     * 复制而不是记住原路径：原图在别处，玩家随时可能挪走或删掉，而壁纸是要长期用的。
     * 重名时挂序号而不覆盖——玩家看到的是两张都在，而不是"我导入了一张，原来那张不见了"。
     *
     * 只收 PNG：加载这条路走的是 {@code ImageIO.read}，别的格式读出来也能转纹理，
     * 但壁纸目录的约定一直是 PNG（见类注释），混着放会让"为什么这张能认那张不能"变得难解释。
     */
    public static String importFile(Path source) {
        if (source == null || !Files.isRegularFile(source)) return null;

        String name = source.getFileName().toString();
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) return null;

        try {
            Path dir = directory();
            Path target = dir.resolve(name);
            for (int i = 2; Files.exists(target); i++) {
                name = name.substring(0, name.length() - 4) + "-" + i + ".png";
                target = dir.resolve(name);
            }
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            LOGGER.info("已导入壁纸: {}", target);
            return target.getFileName().toString();
        } catch (IOException e) {
            LOGGER.warn("导入壁纸失败 {}: {}", source, e.getMessage());
            return null;
        }
    }

    //  查询

    public static List<WallpaperEntry> getWallpapers() {
        return Collections.unmodifiableList(WALLPAPERS);
    }


    public static WallpaperEntry findEntry(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        return WALLPAPERS.stream()
                .filter(w -> w.fileName().equals(fileName))
                .findFirst()
                .orElse(null);
    }


    public static WallpaperEntry getWallpaper(int index) {
        return (index >= 0 && index < WALLPAPERS.size()) ? WALLPAPERS.get(index) : null;
    }
}
