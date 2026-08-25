package com.november.mcphone.feature.browser.client;

import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.feature.browser.client.BrowserBackends;
import com.november.mcphone.feature.browser.client.BrowserScreen;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * 浏览器 App：唯一跳出机身的 App，打开一块占屏幕九成的面板，网页渲染交给 {@link IBrowser} 后端。
 * 贴图: assets/mcphone/textures/app/browser.png (20×20)
 */
public final class BrowserApp extends PhoneApp {

    public BrowserApp() {
        super("browser");
    }

    // 前置只看"装没装"、不看"此刻能不能用"：MCEF 要先下载原生库才就绪，而 App 目录只在启动时构建一次，
    // 按可用性登记的话它这一局都不会出现
    @Override
    public List<RequiredMod> requiredMods() {
        return List.of(new RequiredMod(BrowserBackends.MCEF_MODID, "MCEF"));
    }

    @Override
    public void onPress() {
        Minecraft mc = Minecraft.getInstance();
        // 当前界面就是手机，记成 parent，浏览器关掉后能回去
        mc.setScreen(new BrowserScreen(mc.screen));
    }
}
