package com.november.mcphone.feature.music.client;

import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.core.client.PhoneScreen;
import net.minecraft.client.Minecraft;

/**
 * 音乐 App —— 手机音乐播放器。
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
