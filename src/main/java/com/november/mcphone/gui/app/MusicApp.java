package com.november.mcphone.gui.app;

import net.minecraft.client.Minecraft;
import com.november.mcphone.gui.PhoneScreen;

/**
 * 音乐 App —— 手机音乐播放器。
 *
 * 功能：
 * - 播放原版唱片音乐（自动从物品注册表发现所有 RecordItem）
 * - 播放自定义 WAV 文件（放入 config/mcphone/music/）
 * - OGG 格式建议通过资源包加载
 *
 * 贴图: assets/mcphone/textures/gui/app_icon_music.png (20×20)
 */
public final class MusicApp extends PhoneApp {

    public MusicApp() {
        super("music");
    }

    @Override
    public void onPress() {
        if (Minecraft.getInstance().screen instanceof PhoneScreen ps) {
            ps.navigateTo(PhoneScreen.Mode.MUSIC_PLAYER);
        }
    }
}
