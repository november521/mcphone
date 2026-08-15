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
    private static boolean scanned = false;

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

    public static void scan() {
        if (scanned) return;
        scanned = true;

        Path dir = Path.of(WALLPAPER_DIR);
        if (!Files.isDirectory(dir)) {
            try {
                Files.createDirectories(dir);
                LOGGER.info("已创建壁纸目录: {}", dir.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.warn("无法创建壁纸目录: {}", e.getMessage());
            }
            return;
        }

        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().toLowerCase().endsWith(".png"))
                  .sorted()
                  .forEach(WallpaperStore::loadWallpaper);
        } catch (IOException e) {
            LOGGER.warn("扫描壁纸目录失败: {}", e.getMessage());
        }

        LOGGER.info("已加载 {} 张壁纸", WALLPAPERS.size());
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

            // 注册为动态纹理 —— 使用图片原始尺寸
            String texKey = "wp_" + displayName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
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
