package com.november.mcphone.gui.app;

import com.november.mcphone.core.client.PhoneScreen;
import net.minecraft.client.Minecraft;
import com.november.mcphone.core.client.PhoneApp;

/**
 * 聊天 App —— 联系人与消息合并在一起，形如常见的即时通讯软件。
 *
 * 取代了原先分开的"消息"与"联系人"两个占位 App：手机主屏一行只放
 * 4 个图标，而这两者在任何真实手机上都是同一个 App 的两个部分，
 * 分开占两格既浪费又反直觉。
 *
 * 数据全在服务端，本地只有一份用于渲染的快照，见 ChatClientCache。
 *
 * 贴图: assets/mcphone/textures/app/chat.png (20×20)
 */
public final class ChatApp extends PhoneApp {

    public ChatApp() {
        super("chat");
    }

    @Override
    public void onPress() {
        if (Minecraft.getInstance().screen instanceof PhoneScreen ps) {
            ps.navigateTo(PhoneScreen.Mode.CHAT);
        }
    }
}
