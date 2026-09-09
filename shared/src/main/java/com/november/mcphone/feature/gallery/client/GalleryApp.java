package com.november.mcphone.feature.gallery.client;

import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.core.client.PhoneScreen;
import net.minecraft.client.Minecraft;

/**
 * 相册 App：浏览 <游戏目录>/screenshots/。相机拍的与 F2 截的不作区分，按时间倒序。
 * 界面见 {@link Gallery}，扫描与缩略图缓存见 {@link PhotoLibrary}。
 * 贴图: assets/mcphone/textures/app/gallery.png (20×20)
 */
public final class GalleryApp extends PhoneApp {

    public GalleryApp() {
        super("gallery");
    }

    @Override
    public void onPress() {
        // 相册是手机内的一个模式、不另开 Screen；navigateTo 进入时会重扫目录，刚拍的照片立刻可见
        if (Minecraft.getInstance().screen instanceof PhoneScreen ps) {
            ps.navigateTo(PhoneScreen.Mode.GALLERY);
        }
    }
}
