package com.november.mcphone.core.client.home;

import com.november.mcphone.core.client.DeviceMetrics;
import com.november.mcphone.feature.settings.client.WallpaperStore;

import java.awt.image.BufferedImage;

public final class HomeBackdropTest {
    public static void main(String[] args) {
        checkChromeExcludedFromCapture();
        checkWallpaperFillsAllEdges();
        System.out.println("Home backdrop tests passed");
    }

    private static void checkChromeExcludedFromCapture() {
        var phone = HomeBackdrop.DesktopPixels.of(DeviceMetrics.PHONE, 35, 22, 360, 600);
        check(!phone.hasDesktopAt(100, 63), "Phone status glyphs must not be sampled");
        check(phone.hasDesktopAt(100, 64), "Phone desktop starts below status");
        check(phone.hasDesktopAt(100, 579), "Last desktop row is sampled");
        check(!phone.hasDesktopAt(100, 580), "Phone navigation glyphs must not be sampled");

        var tablet = HomeBackdrop.DesktopPixels.of(DeviceMetrics.TABLET, 35, 22, 720, 504);
        check(tablet.hasDesktopAt(712, 525), "Tablet desktop reaches the bottom");
        check(!tablet.hasDesktopAt(713, 525), "Tablet side navigation glyphs must not be sampled");
    }

    private static void checkWallpaperFillsAllEdges() {
        int[] stripes = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFAA00};
        BufferedImage source = new BufferedImage(6, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setRGB(x, y, stripes[y / 2]);
            }
        }
        var wallpaper = new WallpaperStore.WallpaperEntry("fixture.png", "fixture", null, 6, 8, source);
        var tablet = WallpaperStore.sampleScreen(wallpaper, 6, 4, 6, 4, 0xFF101020);
        check(tablet.getRGB(0, 0) == stripes[1], "Tablet top uses the centered wallpaper crop");
        check(tablet.getRGB(5, 3) == stripes[2], "Tablet navigation-side bottom uses the same crop");

        var phone = WallpaperStore.sampleScreen(wallpaper, 6, 8, 6, 8, 0xFF101020);
        check(phone.getRGB(0, 0) == stripes[0], "Phone status uses the top of the wallpaper");
        check(phone.getRGB(5, 7) == stripes[3], "Phone navigation uses the bottom of the wallpaper");
        var fallback = WallpaperStore.sampleScreen(null, 6, 8, 6, 8, 0xFF101020);
        check(fallback.getRGB(5, 7) == 0xFF101020, "Default background fills the complete image");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
