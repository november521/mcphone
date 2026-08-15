package com.november.mcphone.gui.app;

import com.november.mcphone.gui.PhoneScreen;
import net.minecraft.client.Minecraft;

/**
 * 相册 App —— 浏览 <游戏目录>/screenshots/ 下的照片。
 *
 * 相机 App 拍的照片与 F2 随手截的图都在同一目录、格式命名一致，
 * 相册不作区分，一律按时间倒序展示。
 *
 * 界面见 {@link com.november.mcphone.gui.Gallery}，
 * 扫描与缩略图缓存见 {@link com.november.mcphone.gui.PhotoLibrary}。
 *
 * 贴图: assets/mcphone/textures/app/gallery.png (20×20)
 */
public final class GalleryApp extends PhoneApp {

    public GalleryApp() {
        super("gallery");
    }

    /** 非预装：默认不在主屏，玩家可从应用商店下载 */
    @Override
    public boolean isPreinstalled() { return false; }

    @Override
    public void onPress() {
        // 与音乐、商店一致：相册是手机内的一个模式，不另开 Screen。
        // navigateTo 进入时会重扫目录，刚拍的照片立刻可见
        if (Minecraft.getInstance().screen instanceof PhoneScreen ps) {
            ps.navigateTo(PhoneScreen.Mode.GALLERY);
        }
    }
}
