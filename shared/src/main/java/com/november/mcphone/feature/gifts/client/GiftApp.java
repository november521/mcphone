package com.november.mcphone.feature.gifts.client;

import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.ui.IPhonePage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 宿主礼包中心。管理入口由独立名单决定，角标读本地推送缓存。 */
public final class GiftApp implements IPhoneApp {
    private final com.google.gson.JsonObject edit;
    public GiftApp() { this.edit=null; }
    GiftApp(com.google.gson.JsonObject edit) { this.edit=edit; }
    @Override public ResourceLocation getId() { return ResourceLocation.fromNamespaceAndPath("mcphone","gifts"); }
    @Override public Component getDisplayName() { return Component.translatable("mcphone.gift.title"); }
    @Override public ResourceLocation getIconTexture() { return null; }
    @Override public void onPress() {}
    @Override public int getBadgeCount() { return GiftCache.badge(); }
    @Override public IPhonePage openPage() { return edit==null?new GiftPage():new GiftSettingsPage(edit); }
    @Override public void renderIcon(net.minecraft.client.gui.GuiGraphics g,int x,int y,int size,float tick) {
        g.fill(x+1,y+size/3,x+size-1,y+size-1,0xFFB066AA); g.fill(x,y+size/4,x+size,y+size/3+2,0xFFD188C5);
        g.fill(x+size/2-1,y+size/4,x+size/2+2,y+size-1,0xFFFFFFA0);
    }
}
