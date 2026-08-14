package com.november.mcphone.gui.app;

import com.november.mcphone.client.browser.BrowserBackends;
import com.november.mcphone.gui.BrowserScreen;
import net.minecraft.client.Minecraft;

/**
 * 浏览器 App —— 在手机里上网。
 *
 * 唯一一个跳出机身的 App：点开后不是在 120×200 的手机屏幕里画网页（那根本读不
 * 了），而是打开一块占屏幕九成的面板。退出就回到手机。
 *
 * 网页的渲染交给 MCEF，我们只画面板、地址栏与导航按钮，并把鼠标键盘转发过去。
 * 中间隔着一层我们自己的抽象，见 {@link com.november.mcphone.client.browser.IBrowser}。
 *
 * 贴图: assets/mcphone/textures/gui/app_icon_browser.png (20×20)
 */
public final class BrowserApp extends PhoneApp {

    public BrowserApp() {
        super("browser");
    }

    /**
     * 没装 MCEF 就当这个 App 不存在——主屏与应用商店里都不出现。
     *
     * 这里问的是"模组装没装"，不是"后端此刻能不能用"：MCEF 要先下载约 200 MB
     * 原生库才算就绪，那可能要等几分钟。按"能不能用"来登记的话，玩家进游戏时
     * App 不在，几分钟后又冒出来——而目录只在启动时构建一次，实际结果是它这一
     * 局都不会出现。
     *
     * 所以登记看装没装，"还没就绪"由界面自己说明。
     */
    @Override
    public boolean isAvailable() {
        return BrowserBackends.isMcefLoaded();
    }

    @Override
    public void onPress() {
        Minecraft mc = Minecraft.getInstance();
        // 当前界面就是手机，把它记成 parent，浏览器关掉后能回去
        mc.setScreen(new BrowserScreen(mc.screen));
    }
}
