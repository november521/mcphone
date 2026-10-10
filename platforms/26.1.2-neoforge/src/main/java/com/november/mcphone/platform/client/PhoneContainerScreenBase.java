package com.november.mcphone.platform.client;
import com.november.mcphone.platform.client.port.PhoneGraphics;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** 容器交互仍使用原版，仅衔接共用的背景和外壳绘制入口。 */
public abstract class PhoneContainerScreenBase<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {
    protected PhoneContainerScreenBase(T menu,Inventory inventory,Component title,int w,int h) { super(menu,inventory,title,w,h); }
    @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float a) { renderBg(new PhoneGraphics(g),a,x,y); }
    protected abstract void renderBg(PhoneGraphics g,float a,int x,int y);
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float a) { render(new PhoneGraphics(g),x,y,a); }
    public void render(PhoneGraphics g,int x,int y,float a) { super.extractRenderState(g.nativeGraphics(),x,y,a); }
    @Override protected void extractLabels(GuiGraphicsExtractor g,int x,int y) { renderLabels(new PhoneGraphics(g),x,y); }
    protected void renderLabels(PhoneGraphics g,int x,int y) { super.extractLabels(g.nativeGraphics(),x,y); }
    protected void renderTooltip(PhoneGraphics g,int x,int y) { super.extractTooltip(g.nativeGraphics(),x,y); }
}
