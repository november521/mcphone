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

    /**
     * MCEF 是【联动】，不是前置 —— MCphone 自己一个前置都没有，缺了 MCEF 少的是
     * 这一个 App，不是手机开不了机。声明成联动之后玩家照样找得到它：商店的
     * 「联动App」页对当前不可用的 App 会回退来读这里，写明缺的是哪个模组。
     */
    @Override
    public List<RequiredMod> companionMods() {
        return List.of(new RequiredMod(BrowserBackends.MCEF_MODID, "MCEF"));
    }

    /**
     * 只看"装没装"、不看"此刻能不能用"：MCEF 要先下载原生库才就绪，而 App 目录
     * 只在启动时构建一次，按就绪与否登记的话它这一局都不会出现。
     *
     * 必须自己判：联动声明不参与默认的可用性判断（默认实现只看 requiredMods），
     * 照默认走就是"永远可用"，没装 MCEF 时主屏上会多一个点了没反应的图标。
     */
    @Override
    public boolean isAvailable() {
        return BrowserBackends.isMcefLoaded();
    }

    @Override
    public void onPress() {
        Minecraft mc = Minecraft.getInstance();
        // 当前界面就是手机，记成 parent，浏览器关掉后能回去
        mc.setScreen(new BrowserScreen(mc.screen));
    }
}
