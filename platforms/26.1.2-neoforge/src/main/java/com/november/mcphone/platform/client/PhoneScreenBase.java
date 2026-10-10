package com.november.mcphone.platform.client;

import com.november.mcphone.platform.client.port.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;

/** 手机页面的版本入口：26.1 提取渲染状态与输入事件转交给共用页面。 */
public abstract class PhoneScreenBase extends Screen {
    /** 页面决定是否显示整屏背景；普通界面默认保留原版行为。 */
    protected boolean shouldDrawBackground() { return true; }

    protected PhoneScreenBase(Component title) { super(title); }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float a) { render(new PhoneGraphics(g),x,y,a); }
    public void render(PhoneGraphics g,int x,int y,float a) { super.extractRenderState(g.nativeGraphics(),x,y,a); }
    @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float a) {
        if (shouldDrawBackground()) renderBackground(new PhoneGraphics(g),x,y,a);
        else minecraft.gui.extractDeferredSubtitles();
    }
    public void renderBackground(PhoneGraphics g,int x,int y,float a) { super.extractBackground(g.nativeGraphics(),x,y,a); }
    @Override public void resize(int w,int h) { resize(Minecraft.getInstance(),w,h); }
    public void resize(Minecraft mc,int w,int h) { super.resize(w,h); }
    public void init(Minecraft mc,int w,int h) { super.init(w,h); }
    @Override public boolean keyPressed(KeyEvent e) { return keyPressed(e.key(),e.scancode(),e.modifiers()); }
    public boolean keyPressed(int key,int scan,int mods) { return super.keyPressed(new KeyEvent(key,scan,mods)); }
    @Override public boolean keyReleased(KeyEvent e) { return keyReleased(e.key(),e.scancode(),e.modifiers()); }
    public boolean keyReleased(int key,int scan,int mods) { return super.keyReleased(new KeyEvent(key,scan,mods)); }
    @Override public boolean charTyped(CharacterEvent e) {
        var previous=PhoneInputs.character; PhoneInputs.character=e;
        try { return charTyped((char)e.codepoint(),PhoneInputs.modifiers()); }
        finally { PhoneInputs.character=previous; }
    }
    public boolean charTyped(char c,int mods) { return super.charTyped(PhoneInputs.character(c)); }
    @Override public boolean mouseClicked(MouseButtonEvent e,boolean twice) {
        var previous=PhoneInputs.mouse; boolean previousTwice=PhoneInputs.doubleClick;
        PhoneInputs.mouse=e; PhoneInputs.doubleClick=twice;
        try { return mouseClicked(e.x(),e.y(),e.button()); }
        finally { PhoneInputs.mouse=previous; PhoneInputs.doubleClick=previousTwice; }
    }
    public boolean mouseClicked(double x,double y,int b) { return super.mouseClicked(PhoneInputs.mouse(x,y,b),PhoneInputs.doubleClick); }
    @Override public boolean mouseReleased(MouseButtonEvent e) { return mouseReleased(e.x(),e.y(),e.button()); }
    public boolean mouseReleased(double x,double y,int b) { return super.mouseReleased(PhoneInputs.mouse(x,y,b)); }
    @Override public boolean mouseDragged(MouseButtonEvent e,double dx,double dy) { return mouseDragged(e.x(),e.y(),e.button(),dx,dy); }
    public boolean mouseDragged(double x,double y,int b,double dx,double dy) { return super.mouseDragged(PhoneInputs.mouse(x,y,b),dx,dy); }
    @Override public boolean mouseScrolled(double x,double y,double sx,double sy) { return onScroll(x,y,sx,sy)||super.mouseScrolled(x,y,sx,sy); }
    protected boolean onScroll(double x,double y,double sx,double sy) { return false; }
    public static boolean hasControlDown() { return PhoneInputs.control(); }
    public static boolean hasShiftDown() { return PhoneInputs.shift(); }
    public static boolean hasAltDown() { return PhoneInputs.alt(); }
}
