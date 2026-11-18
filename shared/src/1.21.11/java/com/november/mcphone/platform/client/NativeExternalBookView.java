package com.november.mcphone.platform.client;

import com.november.mcphone.platform.client.port.PhoneGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Rendering and scroll entry points for a hosted book view on 1.21.11. */
public final class NativeExternalBookView {
    private NativeExternalBookView() {}

    public static void init(Screen view, Minecraft client, int width, int height) {
        view.init(width, height);
    }

    public static void render(Screen view, PhoneGraphics graphics, int x, int y, float partialTick) {
        view.render(graphics.nativeGraphics(), x, y, partialTick);
    }

    public static boolean scroll(Screen view, double x, double y, double horizontal, double vertical) {
        return view.mouseScrolled(x, y, horizontal, vertical);
    }
}
