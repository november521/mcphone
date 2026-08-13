package com.november.mcphone.gui.app;

import com.november.mcphone.network.OpenEnderChestPacket;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 便携末影箱 App —— 在手机里打开自己的末影箱。
 *
 * 内容就是原版末影箱，与方块末影箱、跨维度完全互通。
 *
 * 点击只发包、不自己开界面：容器菜单必须由服务端建立才有权威性，
 * 界面会在服务端 openMenu 后由原版流程自动弹出。
 *
 * 贴图: assets/mcphone/textures/gui/app_icon_ender_chest.png (20×20)
 */
public final class EnderChestApp extends PhoneApp {

    public EnderChestApp() {
        super("ender_chest");
    }

    @Override
    public void onPress() {
        PacketDistributor.sendToServer(new OpenEnderChestPacket());
    }
}
