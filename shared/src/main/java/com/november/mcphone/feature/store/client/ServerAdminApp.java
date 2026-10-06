package com.november.mcphone.feature.store.client;

import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.core.script.client.ClientAdministration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 独立、不可卸载的管理员入口，当前服务器角色丢失后即时隐藏。 */
public final class ServerAdminApp implements IPhoneApp {
    @Override public ResourceLocation getId(){return ResourceLocation.fromNamespaceAndPath("mcphone","server_admin");}
    @Override public Component getDisplayName(){return Component.literal("服务器管理");}
    @Override public boolean isVisible(){return ClientAdministration.admin();}
    @Override public boolean isSystemApp(){return true;}
    @Override public ResourceLocation getIconTexture(){return null;}
    @Override public void onPress(){}
    @Override public IPhonePage openPage(){return new NativeAdminPage();}
    @Override public void renderIcon(net.minecraft.client.gui.GuiGraphics g,int x,int y,int size,float tick){g.fill(x+2,y+2,x+size-2,y+size-2,0xFF426337);for(int i=0;i<3;i++)g.fill(x+size/4,y+size/4+i*size/5,x+size*3/4,y+size/4+i*size/5+2,0xFFE9F8E2);}
}
