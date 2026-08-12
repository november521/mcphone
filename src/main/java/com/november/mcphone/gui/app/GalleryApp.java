package com.november.mcphone.gui.app;

/**
 * 相册 App —— 目前为占位，待后续实现图片浏览功能。
 *
 * 贴图: assets/mcphone/textures/gui/app_icon_gallery.png (20×20)
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
        // TODO: 后续实现相册功能
    }
}
