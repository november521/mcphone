package com.november.mcphone.platform.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.function.Consumer;

/** Reads the last rendered game frame in memory. The receiver owns the image. */
public final class PhoneDesktopCapture {
    private PhoneDesktopCapture() {}

    public static void capture(Consumer<NativeImage> receiver) {
        var client = Minecraft.getInstance();
        Screenshot.takeScreenshot(client.getMainRenderTarget(), image -> client.execute(() -> receiver.accept(image)));
    }

    public static int readAbgr(NativeImage image, int x, int y) {
        int argb = image.getPixel(x, y);
        return (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | (argb >>> 16 & 0xFF);
    }
}
