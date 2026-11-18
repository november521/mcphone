package com.november.mcphone.core.client;

import net.minecraft.client.gui.GuiGraphics;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** The default wallpaper from the folder demo, rasterized once per device orientation. */
public enum DefaultWallpaper {
    PORTRAIT(DeviceMetrics.PHONE),
    LANDSCAPE(DeviceMetrics.TABLET);

    private final DeviceMetrics metrics;
    private BufferedImage pixels;
    private ImageCodec.Texture texture;

    DefaultWallpaper(DeviceMetrics metrics) {
        this.metrics = metrics;
    }

    public static DefaultWallpaper forScreen(int width, int height) {
        return width >= height ? LANDSCAPE : PORTRAIT;
    }

    void draw(GuiGraphics g, int x, int y, int width, int height) {
        if (texture == null) {
            texture = ImageCodec.upload(ImageCodec.toNative(pixels()), "default_wallpaper/");
        }
        GuiUtil.drawTexture(g, texture.location(), x, y, width, height, texture.width(), texture.height());
    }

    public BufferedImage sample(int width, int height) {
        var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(pixels(), 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private BufferedImage pixels() {
        if (pixels != null) return pixels;
        int width = metrics.screenW();
        int height = metrics.screenH();
        var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        // CSS angles start at north and turn clockwise. The gradient line spans the box's projection.
        double angle = Math.toRadians(154);
        double dx = Math.sin(angle) * width;
        double dy = -Math.cos(angle) * height;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double u = (x + 0.5) / width;
                double v = (y + 0.5) / height;
                double t = (u * dx + v * dy) / (dx + dy);
                int base = t < 0.57
                        ? mix(0x101B47, 0x0C1435, t / 0.57)
                        : mix(0x0C1435, 0x0B162E, (t - 0.57) / 0.43);
                int lower = mix(base, 0x183C58, radialWeight(u, v, 0.09, 1.04, 0.51));
                result.setRGB(x, y, mix(lower, 0x354780, radialWeight(u, v, 0.96, 0.12, 0.52)));
            }
        }
        pixels = result;
        return result;
    }

    private static double radialWeight(double x, double y, double cx, double cy, double stop) {
        // The default CSS ellipse reaches the farthest corner.
        double rx = Math.max(cx, 1 - cx) * Math.sqrt(2);
        double ry = Math.max(cy, 1 - cy) * Math.sqrt(2);
        double distance = Math.hypot((x - cx) / rx, (y - cy) / ry);
        return Math.max(0, 1 - distance / stop);
    }

    private static int mix(int from, int to, double weight) {
        return mixChannel(from >> 16, to >> 16, weight) << 16
                | mixChannel(from >> 8, to >> 8, weight) << 8
                | mixChannel(from, to, weight);
    }

    private static int mixChannel(int from, int to, double weight) {
        return (int) Math.round((from & 255) * (1 - weight) + (to & 255) * weight);
    }
}
