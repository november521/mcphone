package com.november.mcphone.feature.music.client;

import net.minecraft.client.Minecraft;
import com.november.mcphone.core.client.PhoneScreen;
import com.november.mcphone.core.client.PhoneApp;

/**
 * 音乐 App —— 手机音乐播放器。
 *
 * 功能：
 * - 播放原版唱片音乐（读取 JukeboxSong 注册表）
 * - 播放自定义 WAV 文件（放入 config/mcphone/music/）
 * - OGG 格式建议通过资源包加载
 *
 * 贴图: assets/mcphone/textures/app/music.png (20×20)
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
