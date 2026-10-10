package com.november.mcphone.api.client.ui;
import com.november.mcphone.platform.client.port.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** 26.1 原版裁剪已支持位姿；保留对外控件入口并委托原版实现。 */
public final class PhoneMultiLineEditBox  {
    private final MultiLineEditBox box;
    public PhoneMultiLineEditBox(Font f,int x,int y,int w,int h,Component p,Component m) { box=MultiLineEditBox.builder().setX(x).setY(y).setPlaceholder(p).build(f,w,h,m); }
    public void render(PhoneGraphics g,int x,int y,float a) { box.extractRenderState(g.nativeGraphics(),x,y,a); }
    public boolean keyPressed(int key,int scan,int mods) { return box.keyPressed(new KeyEvent(key,scan,mods)); }
    public boolean charTyped(char c,int mods) { return box.charTyped(PhoneInputs.character(c)); }
    public boolean mouseClicked(double x,double y,int b) { return box.mouseClicked(PhoneInputs.mouse(x,y,b),PhoneInputs.doubleClick); }
    public boolean mouseReleased(double x,double y,int b) { return box.mouseReleased(PhoneInputs.mouse(x,y,b)); }
    public boolean mouseDragged(double x,double y,int b,double dx,double dy) { return box.mouseDragged(PhoneInputs.mouse(x,y,b),dx,dy); }
    public String getValue() { return box.getValue(); }
    public void setValue(String value) { box.setValue(value); }
    public void setCharacterLimit(int limit) { box.setCharacterLimit(limit); }
    public void setFocused(boolean value) { box.setFocused(value); }
    public boolean isFocused() { return box.isFocused(); }
    public void setX(int value) { box.setX(value); }
    public void setY(int value) { box.setY(value); }
    public void setWidth(int value) { box.setWidth(value); }
    public void setHeight(int value) { box.setHeight(value); }
    public boolean mouseScrolled(double x,double y,double sx,double sy) { return box.mouseScrolled(x,y,sx,sy); }
    public void tick() {}
}
