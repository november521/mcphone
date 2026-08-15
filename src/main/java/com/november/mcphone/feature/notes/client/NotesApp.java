package com.november.mcphone.feature.notes.client;

import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.core.client.PhoneScreen;
import net.minecraft.client.Minecraft;

/**
 * 记事本 App —— 写点东西记下来。
 *
 * 笔记跟着玩家走，换手机不丢，别人捡到你的手机也看不见。想给别人看就
 * 印一本书出来。
 *
 * 与音乐、相册一致：记事本是手机内的一个模式，不另开 Screen。
 *
 * 贴图: assets/mcphone/textures/app/notes.png (20×20)
 */
public final class NotesApp extends PhoneApp {

    public NotesApp() {
        super("notes");
    }

    @Override
    public void onPress() {
        if (Minecraft.getInstance().screen instanceof PhoneScreen ps) {
            ps.navigateTo(PhoneScreen.Mode.NOTES);
        }
    }
}
