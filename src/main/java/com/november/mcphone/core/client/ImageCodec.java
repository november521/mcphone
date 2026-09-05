package com.november.mcphone.core.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.november.mcphone.MCphone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 手机里所有"把一张外来的图弄进显存"的公共部分：读、缩、转、上传贴图。
 *
 * 为什么要有这一层
 *
 * 相册（{@link com.november.mcphone.feature.gallery.client.PhotoLibrary}）读的是硬盘上的截图，
 * 美西螈的图片消息读的是从服务端收到的一段字节，两者路径不同，但中间那几步——等比缩小、
 * ARGB 转 ABGR、注册成 DynamicTexture——一模一样，而且每一步都有不写下来就会踩的坑
 * （逐级减半、字节序、尺寸要在 register 之前取）。这些东西只该有一份。
 *
 * 线程
 *
 * 读盘、解码、缩放都可以在后台线程做，也应该在后台线程做（大图缩放放主线程会卡帧）。
 * 只有 {@link #upload} 必须在渲染线程——那是 GL 调用。
 *
 * 用 ImageIO 而不是 NativeImage.read
 *
 * 后者走的是 stb 的原生解码器，一段畸形的字节能把整个进程带走；而图片消息里的字节
 * 是【别的玩家发来的】。ImageIO 是纯 Java 的，解不动只会抛异常，接得住。
 */
public final class ImageCodec {

    private ImageCodec() {}

    /**
     * 一张已上传到显存的贴图。
     *
     * 必须带上宽高：把图等比塞进一个格子要用 GuiGraphics 的 11 参 blit，而那个重载需要
     * 纹理的真实尺寸才能算出源区域。缩过的图宽高比各不相同（截图有 16:9 也有窗口化的
     * 怪比例），不能写死。
     */
    public record Texture(ResourceLocation location, int width, int height) {}

    /** 一张压好、可以直接发出去的 PNG */
    public record Encoded(byte[] png, int width, int height) {}

    /** 贴图 ResourceLocation 的自增序号，保证路径唯一且字符合法 */
    private static int textureSeq = 0;

    /** 读盘 → 等比缩到长边不超过 maxSide → NativeImage。后台线程调用，失败返回 null */
    public static NativeImage readAndScale(Path path, int maxSide) {
        try (InputStream in = Files.newInputStream(path)) {
            return scaleToNative(ImageIO.read(in), maxSide, path.getFileName().toString());
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 加载图片失败 {}: {}", path.getFileName(), e.getMessage());
            return null;
        } catch (OutOfMemoryError e) {
            // 超大图（如 4K 全景）可能撑爆堆，吞掉当作加载失败，总比整个游戏崩掉强
            MCphone.LOGGER.warn("[MCphone] 图片过大，内存不足: {}", path.getFileName());
            return null;
        }
    }

    /** 同上，但源是内存里的一段字节（图片消息走这条）。解不动返回 null，绝不抛给调用方 */
    public static NativeImage decodeAndScale(byte[] data, int maxSide) {
        try (InputStream in = new ByteArrayInputStream(data)) {
            return scaleToNative(ImageIO.read(in), maxSide, "收到的图片");
        } catch (IOException | RuntimeException e) {
            // RuntimeException 也要接：畸形 PNG 在 ImageIO 里常常是数组越界而不是 IOException
            MCphone.LOGGER.warn("[MCphone] 图片解码失败: {}", e.getMessage());
            return null;
        } catch (OutOfMemoryError e) {
            MCphone.LOGGER.warn("[MCphone] 图片过大，内存不足");
            return null;
        }
    }

    /**
     * 读盘 → 等比缩到长边不超过 maxSide → 编码成 PNG 字节。后台线程调用，失败返回 null。
     *
     * 用 TYPE_INT_RGB 而不是带 alpha 的：截图本来就没有透明像素，多一个通道只是让
     * 编出来的文件更大，而这条路上每一个字节都要过网络。
     */
    public static Encoded encodePng(Path path, int maxSide) {
        try (InputStream in = Files.newInputStream(path)) {
            BufferedImage src = ImageIO.read(in);
            if (src == null) return null;

            BufferedImage scaled = scaleDown(src, maxSide);
            BufferedImage opaque = new BufferedImage(
                    scaled.getWidth(), scaled.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = opaque.createGraphics();
            g2.drawImage(scaled, 0, 0, null);
            g2.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(opaque, "png", out)) return null;

            return new Encoded(out.toByteArray(), opaque.getWidth(), opaque.getHeight());
        } catch (IOException | RuntimeException e) {
            MCphone.LOGGER.warn("[MCphone] 图片压缩失败 {}: {}", path.getFileName(), e.getMessage());
            return null;
        } catch (OutOfMemoryError e) {
            MCphone.LOGGER.warn("[MCphone] 图片过大，内存不足: {}", path.getFileName());
            return null;
        }
    }

    /** 把已就绪的 NativeImage 注册成贴图。必须在渲染线程调用 */
    public static Texture upload(NativeImage image, String namePrefix) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                MCphone.MODID, namePrefix + (textureSeq++));

        // 尺寸要在 register 之前取：DynamicTexture 接管 NativeImage 后不该再碰它
        int width = image.getWidth();
        int height = image.getHeight();
        Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
        return new Texture(location, width, height);
    }

    /** 归还显存。贴图不再显示时必须调，否则它会一直留在 TextureManager 里 */
    public static void release(Texture texture) {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(texture.location());
        }
    }

    private static NativeImage scaleToNative(BufferedImage src, int maxSide, String what) {
        if (src == null) {
            MCphone.LOGGER.warn("[MCphone] 无法识别的图片: {}", what);
            return null;
        }
        return toNative(scaleDown(src, maxSide));
    }

    /** ARGB → ABGR（NativeImage 的内部字节序） */
    private static NativeImage toNative(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();

        NativeImage out = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                out.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return out;
    }

    /**
     * 等比缩小到长边不超过 maxSide。
     *
     * 逐次减半而不是一步到位：从 1920 直接 bilinear 缩到 96，每个目标像素只采样了源图
     * 2×2 的范围，等于把 99% 的像素直接扔掉，结果全是噪点。每次只缩一半则相邻两级的
     * 2×2 采样恰好覆盖全部像素，等效于逐级均值，缩略图就干净了。
     */
    public static BufferedImage scaleDown(BufferedImage src, int maxSide) {
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
}
