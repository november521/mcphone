package com.november.mcphone.core.client;

import net.minecraft.client.gui.Font;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 各界面共用的小工具 —— 命中判定、文字截断、时间显示。
 *
 * ================================================================
 * 为什么要有这个类
 * ================================================================
 *
 * 这三件事此前散在各个界面里各写一遍，而且是逐字符相同的一遍：
 *
 *   矩形命中判定  5 份（AppStore、CompanionApps、BrowserScreen、
 *                      WallpaperPicker、MusicPlayer），三个不同的名字：
 *                      hit / isHover / mxInRect
 *   文字截断      5 份（NotesList、ChatList、ChatAddContact、
 *                      ChatConversation、PhoneToast）一字不差，
 *                      外加 AppDetail.trim 一份【不一样】的
 *   时间显示      2 份（NotesList、ChatList），连格式串都各定义一遍
 *
 * 复制本身还不是最糟的。最糟的是 AppDetail.trim 那一份悄悄长得不一样：
 * 它写死 maxW - 6 当省略号宽度（真实宽度取决于字体），也没有
 * maxWidth <= 0 的保护。于是同一句"名字太长就截断"，商店详情页的行为
 * 与其余五处不同——而这种不同没人看得出来，只会觉得"这一页的截断有点怪"。
 *
 * util 包里的 {@link com.november.mcphone.util.TextSanitizer} 是同一个理由
 * 抽出来的先例。
 *
 * ================================================================
 * 为什么放在 core.client 而不是 util
 * ================================================================
 *
 * 这里的方法碰 {@link Font}，那是 net.minecraft.client 下的类型。util 包
 * 的类在专用服务器上也会被加载（TextSanitizer 就在服务端校验聊天正文），
 * 把客户端类型带进去，dist 隔离校验会当场拦下——而它拦的正是"这个类一旦
 * 在专用服务器上被加载就崩服"。
 *
 * 放在含 /client/ 的包里，那道校验按路径放行，也和这些方法的真实用途一致：
 * 它们只服务于绘制。
 */
public final class GuiUtil {

    private GuiUtil() {}

    // ============================================================
    //  命中判定
    // ============================================================

    /**
     * 点在这个矩形里吗。
     *
     * 收 double 而不是 int：Minecraft 的鼠标事件给的就是 double
     * （BrowserScreen 那一份因此不得不单独写成 double 版）。传 int 进来
     * 会自动加宽，两种调用方都不必改写自己的坐标类型。
     *
     * 右下边界【含】，与此前五份实现一致：改成不含的话，紧挨着的两个
     * 按钮之间会出现一条一像素宽、点了没反应的缝。
     */
    public static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    // ============================================================
    //  文字
    // ============================================================

    /**
     * 太长就截断，末尾补省略号。
     *
     * 省略号的宽度按字体真实量出来（font.width("…")），不写死一个像素数：
     * 写死的那一份在中文字体下留的位置不够，省略号会被挤出可用宽度。
     *
     * @param maxWidth 可用宽度（像素）。小于等于 0 时返回空串——那说明
     *                 调用方算出来的可用空间已经没有了，硬画会画到别人身上
     */
    public static String truncate(Font font, String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    // ============================================================
    //  时间
    // ============================================================

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM-dd");

    /**
     * 列表里那一列时间：今天的只显示时刻，别的日子显示月日。
     *
     * 这是手机上通行的做法——今天的消息看几点，前几天的看是哪天，
     * 而"11-03 14:22"这种完整写法在 120 像素宽的屏幕上占不下。
     *
     * 用本机时区而不是 UTC：玩家看的是自己的钟。
     *
     * @param time 毫秒时间戳。小于等于 0 视为"没有时间"，返回空串
     */
    public static String formatTime(long time) {
        if (time <= 0) return "";

        ZoneId zone = ZoneId.systemDefault();
        var dateTime = Instant.ofEpochMilli(time).atZone(zone);
        return dateTime.toLocalDate().equals(LocalDate.now(zone))
                ? dateTime.format(TIME_FORMAT)
                : dateTime.format(DATE_FORMAT);
    }
}
