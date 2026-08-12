package com.november.mcphone.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 壁纸存储 —— 扫描 config/mcphone/wallpapers/ 目录，加载 PNG 为纹理。
 *
 * 玩家使用流程：
 * 1. 把 120×200 的 PNG 图片放入 config/mcphone/wallpapers/
 * 2. 打开手机 → 设置 → 选择壁纸
 *
 * 性能说明：
 * - 纹理只在客户端加载一次（游戏启动时扫描）
 * - 不在每帧创建任何新对象
 * - wallpaper 列表是不可变的只读列表
 */
public final class WallpaperStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("mcphone/WallpaperStore");

    /** 壁纸目录：config/mcphone/wallpapers/ */
    private static final String WALLPAPER_DIR = "config/mcphone/wallpapers";

    /** 表示"无壁纸"的特殊键 —— 使用纯色背景 */
    public static final String NO_WALLPAPER = "";

    /** 已加载的壁纸列表（线程安全，初始化后只读） */
    private static final List<WallpaperEntry> WALLPAPERS = new ArrayList<>();
    private static boolean scanned = false;

    private WallpaperStore() {}

    // ============================================================
    //  数据类
    // ============================================================

    /**
     * 单张壁纸的元数据。
     * @param fileName  文件名（含扩展名），如 "mountains.png"
     * @param displayName 显示名称（去掉扩展名），如 "mountains"
     * @param texture   Minecraft 纹理对象
     */
    public record WallpaperEntry(String fileName, String displayName, ResourceLocation texture) {}

    // ============================================================
    //  扫描 & 加载
    // ============================================================

    /**
     * 扫描壁纸目录并加载所有 PNG。
     * 在客户端初始化阶段调用一次。
     */
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
        String displayName = fileName.substring(0, fileName.length() - 4); // 去掉 .png

        try (InputStream in = Files.newInputStream(path)) {
            BufferedImage awtImage = ImageIO.read(in);
            if (awtImage == null) {
                LOGGER.warn("无法读取壁纸图片: {}", fileName);
                return;
            }

            // 转换为 Minecraft NativeImage
            NativeImage nativeImage = new NativeImage(
                    awtImage.getWidth(), awtImage.getHeight(), false);

            for (int y = 0; y < awtImage.getHeight(); y++) {
                for (int x = 0; x < awtImage.getWidth(); x++) {
                    int argb = awtImage.getRGB(x, y);
                    // ARGB → ABGR (Minecraft 内部格式)
                    int a = (argb >> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    // ABGR = (a << 24) | (b << 16) | (g << 8) | r
                    int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                    nativeImage.setPixelRGBA(x, y, abgr);
                }
            }

            // 注册为动态纹理
            String texKey = "wallpaper_" + displayName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
            ResourceLocation texLoc = ResourceLocation.fromNamespaceAndPath("mcphone", texKey);
            DynamicTexture dynTex = new DynamicTexture(nativeImage);
            Minecraft.getInstance().getTextureManager().register(texLoc, dynTex);

            WALLPAPERS.add(new WallpaperEntry(fileName, displayName, texLoc));
            LOGGER.debug("已加载壁纸: {} ({}×{})", fileName, awtImage.getWidth(), awtImage.getHeight());

        } catch (IOException e) {
            LOGGER.warn("加载壁纸失败: {} - {}", fileName, e.getMessage());
        }
    }

    // ============================================================
    //  查询
    // ============================================================

    /** 获取所有壁纸的只读列表 */
    public static List<WallpaperEntry> getWallpapers() {
        return Collections.unmodifiableList(WALLPAPERS);
    }

    /** 根据文件名查找壁纸纹理，找不到返回 null */
    public static ResourceLocation findTexture(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        return WALLPAPERS.stream()
                .filter(w -> w.fileName().equals(fileName))
                .findFirst()
                .map(WallpaperEntry::texture)
                .orElse(null);
    }

    /** 获取壁纸数量 */
    public static int getWallpaperCount() {
        return WALLPAPERS.size();
    }

    /** 获取指定索引的壁纸，越界返回 null */
    public static WallpaperEntry getWallpaper(int index) {
        return (index >= 0 && index < WALLPAPERS.size()) ? WALLPAPERS.get(index) : null;
    }
}
