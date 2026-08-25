package com.november.mcphone.feature.notes.client;

import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.core.client.PhoneScreen;
import net.minecraft.client.Minecraft;

/** 记事本 App，手机内的一个模式。贴图: assets/mcphone/textures/app/notes.png (20×20) */
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
