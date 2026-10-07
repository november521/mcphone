package com.november.mcphone.core.script.client;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.*;
import java.util.function.BooleanSupplier;

/** 由已校验的宿主 HIGH 通知创建；连接、开关或保留记录失效时立即隐藏。 */
public final class NotificationToast implements Toast {
    private final String title,body;
    private final BooleanSupplier visible;
    public NotificationToast(String title,String body,BooleanSupplier visible){this.title=title;this.body=body;this.visible=visible;}
    @Override public int width(){return 160;}
    @Override public int height(){return 32;}
    @Override public Visibility render(GuiGraphics g,ToastComponent component,long elapsed){
        if(elapsed>=5000||!visible.getAsBoolean())return Visibility.HIDE;
        g.fill(0,0,160,32,PhoneTheme.COLOR_TOAST_BG);
        var font=net.minecraft.client.Minecraft.getInstance().font;
        g.drawString(font,font.plainSubstrByWidth(title,150),5,5,PhoneTheme.FONT_COLOR_TOAST_TITLE,false);
        g.drawString(font,font.plainSubstrByWidth(body,150),5,18,PhoneTheme.FONT_COLOR_TOAST,false);
        return Visibility.SHOW;
    }
}
