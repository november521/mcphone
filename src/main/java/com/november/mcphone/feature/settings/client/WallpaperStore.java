package com.november.mcphone.feature.settings.client;

import com.mojang.blaze3d.platform.NativeImage;
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

    // ============================================================
    //  数据类 —— 记录图片原始宽高
    // ============================================================

    public record WallpaperEntry(
            String fileName,
            String displayName,
            ResourceLocation texture,
            int imageWidth,
            int imageHeight
    ) {}

    // ============================================================
    //  扫描 & 加载
    // ============================================================

    /** 客户端启动时扫一次，让第一次开机就有壁纸可选 */
    public static void scan() {
        refresh();
    }

    /**
     * 重扫壁纸目录 —— 每次打开「更换壁纸」都调。
     *
     * ============================================================
     * 为什么必须能重扫
     * ============================================================
     *
     * 原先只在客户端启动时扫一遍，之后往目录里放的图要重启游戏才认。
     * 而"把图拷进 wallpapers 文件夹然后马上想换上"恰恰是这个功能唯一的
     * 用法——玩家不会为了换张壁纸重启一次游戏。
     *
     * ============================================================
     * 增量，不是推倒重来
     * ============================================================
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

    // ============================================================
    //  查询
    // ============================================================

    public static List<WallpaperEntry> getWallpapers() {
        return Collections.unmodifiableList(WALLPAPERS);
    }

    public static ResourceLocation findTexture(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        return WALLPAPERS.stream()
                .filter(w -> w.fileName().equals(fileName))
                .findFirst()
                .map(WallpaperEntry::texture)
                .orElse(null);
    }

    public static WallpaperEntry findEntry(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        return WALLPAPERS.stream()
                .filter(w -> w.fileName().equals(fileName))
                .findFirst()
                .orElse(null);
    }

    public static int getWallpaperCount() {
        return WALLPAPERS.size();
    }

    public static WallpaperEntry getWallpaper(int index) {
        return (index >= 0 && index < WALLPAPERS.size()) ? WALLPAPERS.get(index) : null;
    }
}
