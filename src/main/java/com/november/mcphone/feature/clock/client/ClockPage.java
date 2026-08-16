package com.november.mcphone.feature.clock.client;

import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.feature.clock.WorldClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 时钟那一页。
 *
 * ============================================================
 * 版面：一大三小
 * ============================================================
 *
 * 游戏时间放大居中，其余三行小字排在下面。手机屏幕只有 120 像素宽，
 * 什么都一样大的话，玩家点开之后得逐行读才知道哪个是他要的——而九成情况下
 * 他要的就是"现在几点"。
 *
 * ============================================================
 * 时间从哪儿来
 * ============================================================
 *
 * 游戏时间取自客户端的 ClientLevel，服务端会持续同步它，所以联机时也是准的，
 * 不需要我们自己发任何包。这也是这个 App 唯一的好处之一：它一个网络包都不用。
 *
 * 现实时间用系统时区。玩家看的是自己的钟，不是服务器所在地的钟。
 *
 * ============================================================
 * 时间停了怎么办
 * ============================================================
 *
 * doDaylightCycle 关掉时游戏时间会冻住，"还有多久天黑"就成了一个永远不动的
 * 数字。挂着一个不动的倒计时比不显示更让人困惑——玩家会以为界面卡了。
 * 所以那一行会换成"时间已停止"。
 *
 * 判断方式是记住上一帧的 dayTime：连续若干帧一模一样就认为停了。不去读
 * gamerule，因为客户端读不到它——gamerule 只在服务端，问它要还得发包，
 * 而"看它动不动"这件事客户端自己就看得出来。
 */
public final class ClockPage {

    private ClockPage() {}

    private static final int PAD = 6;

    /** 现实时间的格式。秒不显示：一个每秒都在跳的数字会把视线一直拽过去 */
    private static final DateTimeFormatter REAL_TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** 游戏时间放大到这个倍数 */
    private static final float BIG_SCALE = 2.0f;

    // ============================================================
    //  "时间停了吗"
    // ============================================================

    /** 上一次看到的 dayTime */
    private static long lastDayTime = -1;

    /** 连续多少帧没变了 */
    private static int stillFrames;

    /**
     * 连续这么多帧不变就认为时间停了。
     *
     * 60 帧在正常帧率下约等于一秒。游戏时间每 tick 都会 +1，也就是每秒 20 次，
     * 一秒不动必然是真停了。取小了会误判——切出去再切回来、卡一下顿，都可能
     * 让相邻两帧的 dayTime 相同。
     */
    private static final int STILL_THRESHOLD = 60;

    /** 离开这一页时调用，免得下次进来带着上次的判断 */
    public static void reset() {
        lastDayTime = -1;
        stillFrames = 0;
    }

    // ============================================================
    //  绘制
    // ============================================================

    public static void render(GuiGraphics g, int phoneLeft, int phoneTop,
                              int screenW, int screenH, int statusH, int navH, Font font) {

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;
        int y = phoneTop + statusH + 4;

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            // 理论上进不来：没有世界就没有手机界面。写着是为了不在
            // 某个我们没想到的时机（断线的那一帧）抛空指针
            g.drawString(font, Component.translatable("mcphone.clock.no_world").getString(),
                    x, y, PhoneTheme.FONT_COLOR_SUBTLE, false);
            return;
        }

        final long dayTime = level.getDayTime();
        final boolean frozen = updateFrozen(dayTime);

        // ---- 标题 ----
        g.drawString(font, Component.translatable("mcphone.app.clock").getString(),
                x, y, PhoneTheme.FONT_COLOR_TITLE, true);
        y += font.lineHeight + 4;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 8;

        // ---- 游戏时间，大字居中 ----
        String gameTime = String.format("%02d:%02d",
                WorldClock.hour(dayTime), WorldClock.minute(dayTime));
        drawBigCentered(g, font, gameTime, phoneLeft, screenW, y);
        y += (int) (font.lineHeight * BIG_SCALE) + 3;

        // ---- 第几天，居中小字 ----
        // dayNumber 从 0 起（与原版 /time query day 一致），显示时加一：
        // 玩家说的"第一天"是刚出生那天，没人管它叫第 0 天
        String day = Component.translatable(
                "mcphone.clock.day", WorldClock.dayNumber(dayTime) + 1).getString();
        drawCentered(g, font, day, phoneLeft, screenW, y, PhoneTheme.FONT_COLOR_SUBTLE);
        y += font.lineHeight + 8;

        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 6;

        // ---- 现实时间 ----
        y = drawRow(g, font, x, y, w,
                Component.translatable("mcphone.clock.real").getString(),
                LocalTime.now().format(REAL_TIME),
                PhoneTheme.FONT_COLOR_BODY);

        // ---- 天黑 / 天亮倒计时 ----
        y = drawCountdown(g, font, x, y, w, dayTime, frozen);
    }

    /**
     * 天黑还有多久，或者天亮还有多久。
     *
     * 夜里显示的是"天亮还有"——那会儿玩家关心的是反过来那个数。一直显示
     * "距离天黑"的话，天黑之后它会跳到 20 分钟，看着像倒计时坏了。
     */
    private static int drawCountdown(GuiGraphics g, Font font, int x, int y, int w,
                                     long dayTime, boolean frozen) {
        if (frozen) {
            return drawRow(g, font, x, y, w,
                    Component.translatable("mcphone.clock.until").getString(),
                    Component.translatable("mcphone.clock.frozen").getString(),
                    PhoneTheme.FONT_COLOR_SUBTLE);
        }

        boolean night = WorldClock.isNight(dayTime);
        int ticks = night ? WorldClock.ticksUntilSunrise(dayTime)
                          : WorldClock.ticksUntilSunset(dayTime);
        int seconds = WorldClock.toRealSeconds(ticks);

        String label = Component.translatable(
                night ? "mcphone.clock.until_sunrise" : "mcphone.clock.until_sunset").getString();
        String value = String.format("%d:%02d", seconds / 60, seconds % 60);

        // 天快黑了标成警示色。5 分钟是"现在往回走还来得及"的分界
        int color = (!night && seconds <= 300)
                ? PhoneTheme.FONT_COLOR_NOTICE : PhoneTheme.FONT_COLOR_BODY;

        return drawRow(g, font, x, y, w, label, value, color);
    }

    // ============================================================
    //  小工具
    // ============================================================

    /** 一行"标签 …… 值"，值靠右。与关于页同一套排法 */
    private static int drawRow(GuiGraphics g, Font font, int x, int y, int w,
                               String label, String value, int valueColor) {
        g.drawString(font, label, x, y, PhoneTheme.FONT_COLOR_SUBTLE, false);
        g.drawString(font, value, x + w - font.width(value), y, valueColor, false);
        return y + font.lineHeight + 3;
    }

    private static void drawCentered(GuiGraphics g, Font font, String text,
                                     int phoneLeft, int screenW, int y, int color) {
        g.drawString(font, text, phoneLeft + (screenW - font.width(text)) / 2, y, color, false);
    }

    /**
     * 放大居中画一行字。
     *
     * 先 translate 到目标位置再 scale，最后在原点画——不是缩放一个非原点的
     * 坐标。后者会让实际中心比屏幕中线偏，字越长偏得越多。主屏图标下面那行
     * App 名字踩过同一个坑，见 PhoneScreen.drawAppName。
     */
    private static void drawBigCentered(GuiGraphics g, Font font, String text,
                                        int phoneLeft, int screenW, int y) {
        float bigW = font.width(text) * BIG_SCALE;

        g.pose().pushPose();
        g.pose().translate(phoneLeft + (screenW - bigW) / 2f, y, 0);
        g.pose().scale(BIG_SCALE, BIG_SCALE, 1f);
        g.drawString(font, text, 0, 0, PhoneTheme.FONT_COLOR_TITLE, false);
        g.pose().popPose();
    }

    /** 看游戏时间动没动。连续 {@link #STILL_THRESHOLD} 帧不变就算停了 */
    private static boolean updateFrozen(long dayTime) {
        if (dayTime == lastDayTime) {
            if (stillFrames < STILL_THRESHOLD) stillFrames++;
        } else {
            lastDayTime = dayTime;
            stillFrames = 0;
        }
        return stillFrames >= STILL_THRESHOLD;
    }
}
