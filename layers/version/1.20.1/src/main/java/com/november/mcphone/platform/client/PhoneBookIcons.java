package com.november.mcphone.platform.client;
import net.minecraft.client.gui.GuiGraphics;
import vazkii.patchouli.client.book.BookIcon;
/** 可选 Patchouli 图标的绘制签名接缝。 */
public final class PhoneBookIcons {
    private PhoneBookIcons() {}
    public static void draw(BookIcon icon,GuiGraphics g,int x,int y) {
        icon.render(g,x,y);
    }
}
