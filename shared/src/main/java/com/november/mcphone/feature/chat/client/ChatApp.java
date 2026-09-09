package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.core.client.PhoneScreen;
import net.minecraft.client.Minecraft;

/**
 * 美西螈 App：联系人与消息合在一起。数据全在服务端，本地只有 ChatClientCache 的快照。
 * 显示名走翻译键 mcphone.app.chat；构造函数里的 "chat" 是 id，同时钉住贴图路径、
 * SPI 注册与安装记录，改了会让老玩家图标变空白、App 从主屏消失——不许统一。
 * 贴图: assets/mcphone/textures/app/chat.png (20×20)
 */
public final class ChatApp extends PhoneApp {

    public ChatApp() {
        super("chat");
    }

    /** 未读总数走 {@link ChatBadge}：主屏得自己拉摘要，未读数没有推送 */
    @Override
    public int getBadgeCount() {
        return ChatBadge.unreadCount();
    }

    @Override
    public void onPress() {
        if (Minecraft.getInstance().screen instanceof PhoneScreen ps) {
            ps.navigateTo(PhoneScreen.Mode.CHAT);
        }
    }
}
