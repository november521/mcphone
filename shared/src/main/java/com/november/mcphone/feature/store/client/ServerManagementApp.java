package com.november.mcphone.feature.store.client;

import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.ui.IPhonePage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 我的作品与服务器审核入口。是否有审核许可每次开页由服务器确认。 */
public final class ServerManagementApp implements IPhoneApp {
    @Override public ResourceLocation getId(){return ResourceLocation.fromNamespaceAndPath("mcphone","server_management");}
    @Override public Component getDisplayName(){return Component.literal("作品与管理");}
    @Override public ResourceLocation getIconTexture(){return null;}
    @Override public void onPress(){}
    @Override public IPhonePage openPage(){return new ServerManagementPage();}
    @Override public void renderIcon(net.minecraft.client.gui.GuiGraphics g,int x,int y,int size,float tick){
        g.fill(x+2,y+2,x+size-2,y+size-2,0xFF386282);
        for(int i=0;i<3;i++)g.fill(x+size/4,y+size/4+i*size/5,x+size*3/4,y+size/4+i*size/5+2,0xFFE7F1FF);
    }
}
