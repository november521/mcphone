package com.november.mcphone.feature.browser.client;

import com.november.mcphone.api.client.RequiredMod;
import com.november.mcphone.feature.browser.client.BrowserBackends;
import com.november.mcphone.feature.browser.client.BrowserScreen;
import net.minecraft.client.Minecraft;

import java.util.List;
import com.november.mcphone.core.client.PhoneApp;

/**
 * 浏览器 App —— 在手机里上网。
 *
 * 唯一一个跳出机身的 App：点开后不是在 120×200 的手机屏幕里画网页（那根本读不
 * 了），而是打开一块占屏幕九成的面板。退出就回到手机。
 *
 * 网页的渲染交给 MCEF，我们只画面板、地址栏与导航按钮，并把鼠标键盘转发过去。
 * 中间隔着一层我们自己的抽象，见 {@link com.november.mcphone.feature.browser.client.IBrowser}。
 *
 * 贴图: assets/mcphone/textures/app/browser.png (20×20)
 */
public final class BrowserApp extends PhoneApp {

    public BrowserApp() {
        super("browser");
    }

    /**
     * 硬前置：MCEF。没装就当这个 App 不存在——主屏与商店的普通列表里都不出现，
     * 只在商店的「联动 App」那一页里标着"未装"露个面，让玩家知道装了能多个什么。
     *
     * ============================================================
     * 这里说的是"装没装"，不是"此刻能不能用"
     * ============================================================
     *
     * MCEF 要先下载约 200 MB 原生库才算就绪，可能要等几分钟。按"能不能用"来
     * 登记的话，玩家进游戏时 App 不在，几分钟后又冒出来——而目录只在启动时
     * 构建一次，实际结果是它这一局都不会出现。
     *
     * 所以登记只看装没装，"还没就绪"由浏览器界面自己说明。
     *
     * modid 取 BrowserBackends 的常量，不在这儿再写一遍字符串。
     */
    @Override
    public List<RequiredMod> requiredMods() {
        return List.of(new RequiredMod(BrowserBackends.MCEF_MODID, "MCEF"));
    }

    @Override
    public void onPress() {
        Minecraft mc = Minecraft.getInstance();
        // 当前界面就是手机，把它记成 parent，浏览器关掉后能回去
        mc.setScreen(new BrowserScreen(mc.screen));
    }
}
