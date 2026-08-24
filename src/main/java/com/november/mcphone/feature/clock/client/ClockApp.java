package com.november.mcphone.feature.clock.client;

import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.core.client.PhoneScreen;
import net.minecraft.client.Minecraft;

/**
 * 时钟 App —— 游戏里几点了，现实里几点了。
 *
 * 它比原版的时钟多给什么
 *
 * 原版时钟只有一个转盘，看得出大概是白天还是晚上，看不出"几点"，更看不出
 * "还有多久天黑"。而后者才是玩家真正在问的问题：我这一趟矿还挖不挖得完、
 * 现在回地面会不会正撞上怪。
 *
 * 而且原版时钟【在下界会乱转】——那是刻意的设计。但游戏时间在下界照常同步，
 * 所以这里显示的是地表的真实时间。挖矿的人最想知道的那个数，原版偏偏不给。
 *
 * 换算全在 {@link com.november.mcphone.feature.clock.WorldClock}，那个类不碰
 * Minecraft，可以单独跑断言——时间算错了不会崩也不会报错，只会让所有人的
 * 时钟差 6 小时，这种错误必须在上游戏之前就拦住。
 *
 * 贴图: assets/mcphone/textures/app/clock.png (20×20)
 */
public final class ClockApp extends PhoneApp {

    public ClockApp() {
        super("clock");
    }

    /**
     * 与记事本、相册一致：时钟是手机内的一个模式，不另开 Screen。
     *
     * 没有覆盖 openPage()——那条路是给附属模组用的。内建 App 走 Mode，
     * 两套混用的话，"这个 App 的界面在哪儿画"就得看它是哪一种，没有必要。
     */
    @Override
    public void onPress() {
        if (Minecraft.getInstance().screen instanceof PhoneScreen ps) {
            ps.navigateTo(PhoneScreen.Mode.CLOCK);
        }
    }
}
