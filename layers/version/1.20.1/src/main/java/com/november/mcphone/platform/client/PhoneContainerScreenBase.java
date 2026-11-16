package com.november.mcphone.platform.client;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
/** 容器尺寸在构造时确定；新版原版已将这两个字段设为 final。 */
public abstract class PhoneContainerScreenBase<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {
    protected PhoneContainerScreenBase(T menu,Inventory inventory,Component title,int w,int h) {
        super(menu,inventory,title); this.imageWidth=w; this.imageHeight=h;
    }
}
