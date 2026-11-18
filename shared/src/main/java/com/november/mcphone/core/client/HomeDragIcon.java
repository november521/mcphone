package com.november.mcphone.core.client;

import com.november.mcphone.api.client.app.IPhoneApp;
import net.minecraft.client.gui.GuiGraphics;

final class HomeDragIcon {
    private HomeDragIcon() {
    }

    static void draw(GuiGraphics g, IPhoneApp app, int x, int y, int size, float partialTick, float opacity) {
        // 静态贴图和逐帧截取图集的 App 共用原有绘制方式，保留绘制区域和动画帧。
        // 26.1 的 PhoneGraphics 会把调色存进贴图指令；标签和其他形状需直接传 ARGB。
        g.setColor(1.0F, 1.0F, 1.0F, opacity);
        try {
            app.renderIcon(g, x, y, size, partialTick);
        } finally {
            g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
}
