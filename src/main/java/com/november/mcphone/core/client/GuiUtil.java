package com.november.mcphone.core.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

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

    /**
     * 画图标下面那一行名字：按格子宽度截断，再居中缩放。
     *
     * ============================================================
     * 为什么必须截断
     * ============================================================
     *
     * 主屏与商店的格子步距是 28 像素（图标 20 + 间距 8），而名字此前一个
     * 约束都没有：英文的 "Ender Chest" 缩放后仍有 40 像素，直接压在左右
     * 邻居的名字上；中文名多一个字也顶得出去。名字是玩家分辨图标的唯一
     * 依据，糊成一片比截断难受得多。
     *
     * ============================================================
     * 截断必须在缩放【前】的坐标系里做
     * ============================================================
     *
     * 缩放后的 1 像素对应缩放前的 1/scale 像素。拿 cellWidth 直接去截，
     * 0.6 的缩放下会多截掉将近一半，短名字也会莫名其妙带上省略号。
     * 所以先把可用宽度换算回缩放前（除以 scale），截完再缩。
     *
     * ============================================================
     * 变换的顺序也不能改
     * ============================================================
     *
     * 先 translate 到目标位置再 scale，最后在原点画——而不是缩放一个非
     * 原点的坐标。原先主屏那份把缩放锚点放在文字中心却仍从左端起笔，
     * 实际中心比图标中线偏右 nw×scale×(1-scale)/2 像素，名字越长偏得越多。
     * 1.0.42 修过一次，别再重蹈覆辙。
     *
     * @param cellWidth 这一格能占的宽度（图标宽 + 一个格子间距）
     */
    public static void drawIconLabel(GuiGraphics g, Font font, String name,
                                     int iconX, int iconY, int iconSize,
                                     int cellWidth, float scale, int color) {
        String shown = truncate(font, name, Math.round(cellWidth / scale));
        float shownW = font.width(shown) * scale;

        g.pose().pushPose();
        g.pose().translate(iconX + (iconSize - shownW) / 2f, iconY + iconSize + 2, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, shown, 0, 0, color, false);
        g.pose().popPose();
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
