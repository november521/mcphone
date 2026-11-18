package com.november.mcphone.core.client.home;

import com.mojang.blaze3d.platform.NativeImage;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.DeviceMetrics;
import com.november.mcphone.core.client.DefaultWallpaper;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.HomeLayout.FolderGrid;
import com.november.mcphone.core.client.ImageCodec;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.net.NetworkHandler;
import com.november.mcphone.feature.settings.client.WallpaperStore;
import com.november.mcphone.platform.client.PhoneDesktopCapture;
import net.minecraft.client.gui.GuiGraphics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** A blurred crop of the last home page and the translucent folder panel drawn over it. */
public final class HomeBackdrop {
    private static final int SAMPLE_STEP = 3;
    private static final int[] BLUR_WEIGHTS = {1, 4, 6, 4, 1};
    private static final int RENAME_RADIUS = 6;
    private static final int PANEL_TEXTURE_SCALE = 4;
    private static final int PANEL_TINT_ABGR = 0x00E2D5CB;
    private static final int PANEL_FILL_ALPHA = 39;
    private static final int PANEL_RIM_ALPHA = 55;
    private ImageCodec.Texture panel;
    private ImageCodec.Texture renameSurface;
    private ImageCodec.Texture desktop;
    private DesktopRegion lastDesktop;
    private DesktopRegion capturedDesktop;
    private int generation;
    private boolean desktopActive;

    private record DesktopRegion(float left, float top, float right, float bottom,
                                 int windowWidth, int windowHeight, DeviceMetrics metrics) {}

    record DesktopPixels(int contentTop, int contentRight, int contentBottom) {
        static DesktopPixels of(DeviceMetrics metrics, int left, int top, int width, int height) {
            return new DesktopPixels(
                    top + roundOffset(PhoneTheme.STATUS_BAR_HEIGHT, height, metrics.screenH()),
                    left + roundOffset(metrics.contentWidth(), width, metrics.screenW()),
                    top + roundOffset(metrics.screenH() - metrics.bottomNavHeight(), height, metrics.screenH()));
        }

        boolean hasDesktopAt(int x, int y) {
            return x < contentRight && y >= contentTop && y < contentBottom;
        }
    }

    public void rememberDesktop(int x, int y, DeviceMetrics metrics, float scale, int windowWidth, int windowHeight) {
        float centerX = x + metrics.screenW() / 2;
        float centerY = y + metrics.screenH() / 2;
        lastDesktop = new DesktopRegion(
                centerX + (x - centerX) * scale, centerY + (y - centerY) * scale,
                centerX + (x + metrics.screenW() - centerX) * scale,
                centerY + (y + metrics.screenH() - centerY) * scale,
                windowWidth, windowHeight, metrics);
    }

    public void captureDesktop() {
        clearDesktop();
        desktopActive = true;
        DesktopRegion region = lastDesktop;
        if (region == null || region.windowWidth() <= 0 || region.windowHeight() <= 0) return;
        var wallpaper = WallpaperStore.findEntry(NetworkHandler.WakeholderData.get());
        int request = ++generation;
        try {
            PhoneDesktopCapture.capture(frame -> {
                try (frame) {
                    if (request != generation) return;
                    desktop = blurDesktop(frame, region, wallpaper);
                    capturedDesktop = region;
                } catch (RuntimeException e) {
                    MCphone.LOGGER.warn("[MCphone] Could not blur the desktop for the folder", e);
                }
            });
        } catch (RuntimeException e) {
            MCphone.LOGGER.warn("[MCphone] Could not capture the desktop for the folder", e);
        }
    }

    public boolean drawDesktop(GuiGraphics g, int x, int y, DeviceMetrics metrics) {
        if (desktop == null || !capturedDesktop.metrics().equals(metrics)) return false;
        GuiUtil.drawTexture(g, desktop.location(), x, y, metrics.screenW(), metrics.screenH(),
                desktop.width(), desktop.height());
        return true;
    }

    public void clearDesktop() {
        if (!desktopActive) return;
        desktopActive = false;
        generation++;
        ImageCodec.release(desktop);
        desktop = null;
        capturedDesktop = null;
    }

    public void drawFrostedPanel(GuiGraphics g, FolderGrid grid) {
        if (panel == null) panel = createFrostedTexture(FolderGrid.PANEL_RADIUS);
        drawFrostedSurface(g, panel, grid.panelLeft(), grid.panelTop(),
                grid.panelWidth(), grid.panelHeight(), FolderGrid.PANEL_RADIUS);
    }

    void drawRenameSurface(GuiGraphics g, int x, int y, int width, int height) {
        if (renameSurface == null) renameSurface = createFrostedTexture(RENAME_RADIUS);
        drawFrostedSurface(g, renameSurface, x, y, width, height, RENAME_RADIUS);
    }

    private static void drawFrostedSurface(GuiGraphics g, ImageCodec.Texture texture,
                                           int x, int y, int width, int height, int radius) {
        int designSize = texture.width() / PANEL_TEXTURE_SCALE;
        GuiUtil.drawNineSliceScaled(g, texture.location(), x, y, width, height, texture.width(), texture.height(),
                designSize, designSize, radius);
    }

    public void close() {
        clearDesktop();
        lastDesktop = null;
        ImageCodec.release(panel);
        panel = null;
        ImageCodec.release(renameSurface);
        renameSurface = null;
    }

    private static ImageCodec.Texture blurDesktop(NativeImage frame, DesktopRegion region,
                                                   WallpaperStore.WallpaperEntry wallpaper) {
        float scaleX = frame.getWidth() / (float) region.windowWidth();
        float scaleY = frame.getHeight() / (float) region.windowHeight();
        int left = Math.round(region.left() * scaleX);
        int right = Math.round(region.right() * scaleX);
        int top = Math.round(region.top() * scaleY);
        int bottom = Math.round(region.bottom() * scaleY);
        if (left < 0 || right <= left || right > frame.getWidth()
                || top < 0 || bottom <= top || bottom > frame.getHeight()) return null;

        DeviceMetrics metrics = region.metrics();
        int screenWidth = metrics.screenW();
        int screenHeight = metrics.screenH();
        DesktopPixels pixels = DesktopPixels.of(metrics, left, top, right - left, bottom - top);
        int width = Math.max(1, screenWidth / SAMPLE_STEP);
        int height = Math.max(1, screenHeight / SAMPLE_STEP);
        BufferedImage wallpaperPixels = wallpaper == null
                ? DefaultWallpaper.forScreen(screenWidth, screenHeight).sample(width, height)
                : WallpaperStore.sampleScreen(wallpaper, screenWidth, screenHeight,
                width, height, PhoneTheme.COLOR_SCREEN_BG);
        int[] sampled = new int[width * height];
        for (int y = 0; y < height; y++) {
            int y0 = top + y * (bottom - top) / height;
            int y1 = top + (y + 1) * (bottom - top) / height;
            for (int x = 0; x < width; x++) {
                int x0 = left + x * (right - left) / width;
                int x1 = left + (x + 1) * (right - left) / width;
                sampled[y * width + x] = sample(frame, pixels, x0, y0, x1, y1, wallpaperPixels.getRGB(x, y));
            }
        }
        int[] softened = blur(blur(sampled, width, height, true), width, height, false);
        BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        small.setRGB(0, 0, width, height, softened, 0, width);
        return uploadDesktop(small, screenWidth, screenHeight);
    }

    private static int roundOffset(int logical, int pixelSize, int logicalSize) {
        return Math.round(logical * (float) pixelSize / logicalSize);
    }

    private static int sample(NativeImage frame, DesktopPixels pixels, int x0, int y0, int x1, int y1,
                              int wallpaperArgb) {
        int r = 0, g = 0, b = 0;
        // Four evenly spaced samples per axis also work when a crop is smaller than the sample grid.
        for (int y = 0; y < 4; y++) {
            int sy = y0 + (2 * y + 1) * (y1 - y0) / 8;
            for (int x = 0; x < 4; x++) {
                int sx = x0 + (2 * x + 1) * (x1 - x0) / 8;
                // Rebuild the wallpaper under the live system glyphs before the single blur pass.
                if (pixels.hasDesktopAt(sx, sy)) {
                    int color = PhoneDesktopCapture.readAbgr(frame, sx, sy);
                    r += color & 255;
                    g += color >> 8 & 255;
                    b += color >> 16 & 255;
                } else {
                    r += wallpaperArgb >> 16 & 255;
                    g += wallpaperArgb >> 8 & 255;
                    b += wallpaperArgb & 255;
                }
            }
        }
        return (r / 16 << 16) | (g / 16 << 8) | b / 16;
    }

    private static int[] blur(int[] input, int width, int height, boolean horizontal) {
        int[] output = new int[input.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = 0, g = 0, b = 0;
                for (int i = 0; i < BLUR_WEIGHTS.length; i++) {
                    int offset = i - BLUR_WEIGHTS.length / 2;
                    int sx = horizontal ? Math.max(0, Math.min(width - 1, x + offset)) : x;
                    int sy = horizontal ? y : Math.max(0, Math.min(height - 1, y + offset));
                    int color = input[sy * width + sx];
                    int weight = BLUR_WEIGHTS[i];
                    r += (color >> 16 & 255) * weight;
                    g += (color >> 8 & 255) * weight;
                    b += (color & 255) * weight;
                }
                output[y * width + x] = (r / 16 << 16) | (g / 16 << 8) | b / 16;
            }
        }
        return output;
    }

    private static ImageCodec.Texture uploadDesktop(BufferedImage small, int width, int height) {
        BufferedImage smooth = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D renderer = smooth.createGraphics();
        try {
            renderer.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            renderer.drawImage(small, 0, 0, width, height, null);
        } finally {
            renderer.dispose();
        }

        NativeImage pixels = new NativeImage(width, height, false);
        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = smooth.getRGB(x, y);
                    int abgr = 0xFF000000 | (argb & 255) << 16 | (argb & 0xFF00) | (argb >> 16 & 255);
                    pixels.setPixelRGBA(x, y, abgr);
                }
            }
            return ImageCodec.upload(pixels, "home_folder_desktop_");
        } catch (RuntimeException | Error e) {
            pixels.close();
            throw e;
        }
    }

    private static ImageCodec.Texture createFrostedTexture(int radius) {
        int designSize = 2 * radius + 4;
        int size = designSize * PANEL_TEXTURE_SCALE;
        double halfSize = designSize / 2.0;
        double flatExtent = halfSize - radius;
        NativeImage image = new NativeImage(size, size, false);
        for (int y = 0; y < size; y++) {
            double dy = Math.max(0, Math.abs((y + 0.5) / PANEL_TEXTURE_SCALE - halfSize) - flatExtent);
            for (int x = 0; x < size; x++) {
                double dx = Math.max(0, Math.abs((x + 0.5) / PANEL_TEXTURE_SCALE - halfSize) - flatExtent);
                double distance = Math.hypot(dx, dy) - radius;
                double coverage = Math.max(0, Math.min(1, 0.5 - distance * PANEL_TEXTURE_SCALE));
                double rim = Math.max(0, Math.min(1, (distance + 0.75) * PANEL_TEXTURE_SCALE));
                int alpha = (int) Math.round(coverage * (PANEL_FILL_ALPHA + (PANEL_RIM_ALPHA - PANEL_FILL_ALPHA) * rim));
                image.setPixelRGBA(x, y, alpha << 24 | PANEL_TINT_ABGR);
            }
        }
        return ImageCodec.upload(image, "home_folder_frosted_panel_");
    }
}
