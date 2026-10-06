package com.november.mcphone.feature.notifications.client;

import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.core.script.client.ClientNotifications;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 原生通知中心不允许脚本隐藏通知来源。 */
public final class NotificationsApp implements IPhoneApp {
    @Override public ResourceLocation getId(){return ResourceLocation.fromNamespaceAndPath("mcphone","notifications");}
    @Override public Component getDisplayName(){return Component.translatable("mcphone.notify.title");}
    @Override public ResourceLocation getIconTexture(){return null;}
    @Override public void renderIcon(net.minecraft.client.gui.GuiGraphics g,int x,int y,int size,float tick){g.fill(x+size/5,y+size/5,x+size*4/5,y+size*4/5,0xffd09525);g.fill(x+size/3,y+size*4/5,x+size*2/3,y+size*9/10,0xffedbf62);}
    @Override public void onPress(){ }
    @Override public int getBadgeCount(){return ClientNotifications.total();}
    @Override public IPhonePage openPage(){return new NotificationsPage();}
}
