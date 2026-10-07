package com.november.mcphone.feature.escrow.client;

import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.ui.IPhonePage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 托管与扣除的确认页面属于宿主，App 不能绘制或替换。 */
public final class ItemEscrowApp implements IPhoneApp {
    @Override public ResourceLocation getId(){return ResourceLocation.fromNamespaceAndPath("mcphone","item_escrow");}
    @Override public Component getDisplayName(){return Component.translatable("mcphone.escrow.title");}
    @Override public ResourceLocation getIconTexture(){return null;}
    @Override public void renderIcon(net.minecraft.client.gui.GuiGraphics g,int x,int y,int size,float tick){g.fill(x+1,y+size/4,x+size-1,y+size*3/4,0xFFAA8844);g.fill(x+size/3,y,x+size*2/3,y+size,0xFFEECC66);}
    @Override public void onPress(){}
    @Override public IPhonePage openPage(){return new ItemEscrowPage();}
}
