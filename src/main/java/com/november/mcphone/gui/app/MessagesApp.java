package com.november.mcphone.gui.app;

import net.minecraft.network.chat.Component;

/**
 * 消息 App —— 目前为占位，待后续实现完整聊天功能。
 *
 * 贴图: assets/mcphone/textures/gui/app_icon_messages.png (20×20)
 */
public final class MessagesApp extends PhoneApp {

    public MessagesApp() {
        super("messages", "消息");
    }

    @Override
    public void onPress() {
        // TODO: 后续实现消息系统
    }
}
