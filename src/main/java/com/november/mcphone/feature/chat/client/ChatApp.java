package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.core.client.PhoneScreen;
import net.minecraft.client.Minecraft;

/**
 * 美西螈 App —— 联系人与消息合并在一起，形如常见的即时通讯软件。
 *
 * 显示名叫「美西螈」，内部 id 仍是 chat —— 这两个不许统一
 *
 * 玩家看到的名字来自翻译键 mcphone.app.chat，改它只影响界面上那几个字。
 * 而构造函数里那个 "chat" 是【键】，同时钉住三样东西：
 *
 *   贴图路径     assets/mcphone/textures/app/chat.png
 *   SPI 注册     META-INF/services/...IPhoneApp 里登记的类名
 *   安装记录     config/mcphone/installed/&lt;存档&gt;.json 里存的就是这个 id
 *
 * 把 id 一起"改干净"的话，已经在玩的人下次进游戏会看到：图标变成空白方框，
 * App 从主屏消失（安装记录对不上号，当成没装过）。类名与包名同理。
 *
 * 名字以后再改也一样，只动 lang，不动 id。
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
