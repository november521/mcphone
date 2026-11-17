package com.november.mcphone.platform.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.function.Consumer;

/** Reads the last rendered game frame in memory. The receiver owns the image. */
public final class PhoneDesktopCapture {
    private PhoneDesktopCapture() {}

    public static void capture(Consumer<NativeImage> receiver) {
        receiver.accept(Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget()));
    }

    public static int readAbgr(NativeImage image, int x, int y) {
        return image.getPixelRGBA(x, y);
    }
}
