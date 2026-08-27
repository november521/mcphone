package com.november.mcphone.feature.weather.client;

import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.core.client.PhoneScreen;
import net.minecraft.client.Minecraft;

/**
 * 天气 App —— 现在什么天，这种天适合干什么。
 *
 * 后半句才是它存在的理由
 *
 * "现在下不下雨"抬头就看得见，做个 App 说这个没有意义。有意义的是它接着
 * 说的那句：下雨了适合去钓鱼（雨天上钩快），打雷了是抓高压苦力怕的时候，
 * 下雪了拿把铲子能收雪堆雪傀儡。
 *
 * 这些都是真实存在的机制，不是凑出来的吉利话。玩家看完能立刻去做一件事，
 * 而不只是"哦，下雨了"。
 *
 * 判定逻辑全在 {@link com.november.mcphone.feature.weather.Weather}，那个类
 * 不碰 Minecraft，可以单独跑断言——沙漠、雪山、下界这几种情况在自己的存档
 * 里根本试不出来，而判错了不会崩，手机只会在沙漠里说"正在下雨"。
 *
 * 贴图: assets/mcphone/textures/app/weather.png (20×20)
 */
public final class WeatherApp extends PhoneApp {

    public WeatherApp() {
        super("weather");
    }

    @Override
    public void onPress() {
        if (Minecraft.getInstance().screen instanceof PhoneScreen ps) {
            ps.navigateTo(PhoneScreen.Mode.WEATHER);
        }
    }
}
