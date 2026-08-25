package com.november.mcphone.core.client;

import com.november.mcphone.core.net.NetworkHandler;
import com.november.mcphone.feature.settings.client.WallpaperStore;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.Slot;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 手机机身的绘制 —— 外壳、壁纸、状态栏、导航栏。
 *
 * 抽成静态方法是因为不止一个界面要画手机：{@link PhoneScreen} 是普通
 * Screen，而带格子的界面必须继承原版的 AbstractContainerScreen 才能
 * 白拿物品交换逻辑，两者没有共同基类可放这些代码。壁纸那段等比裁剪
 * 尤其不能抄两遍——它的 blit 参数顺序踩过坑（见下方注释）。
 *
 * 所有坐标参数都是"屏幕内区域左上角"，即不含边框。边框由本类自己往
 * 外扩，调用方不必关心。
 */
public final class PhoneChassis {

    private PhoneChassis() {}

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 画外壳与屏幕背景（壁纸或纯色），尺寸为标准竖屏机身。
     *
     * @param phoneLeft 屏幕内区域左上角 X（不含边框）
     * @param phoneTop  屏幕内区域左上角 Y（不含边框）
     */
    public static void drawScreenBackground(GuiGraphics g, int phoneLeft, int phoneTop) {
        drawScreenBackground(g, phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT);
    }

    /** 标准竖屏机身的外壳。画在最上层，理由见 {@link #drawFrame} */
    public static void drawFrame(GuiGraphics g, int phoneLeft, int phoneTop) {
        drawFrame(g, phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT);
    }

    /**
     * 容器界面的整块底 —— 外壳与壁纸、压暗的蒙版、每个格子的底板。
     *
     * 抽出来是因为带格子的界面不止一个（末影箱、唱片仓），而这三步谁少画
     * 一步都会立刻看出来：不画蒙版则亮壁纸上的白字读不了，不画底板则格子
     * 在花壁纸上没有边界。本类的存在理由就是"这种东西不能抄两遍"。
     *
     * @param slots 菜单里的全部格子，用来画底板。不活跃的跳过
     */
    public static void drawContainerBackdrop(GuiGraphics g, int leftPos, int topPos,
                                             int imageWidth, int imageHeight,
                                             Iterable<Slot> slots) {
        // 壁纸按本界面的尺寸绘制。外壳不在这儿 —— 它要画在最上层，
        // 由各界面在 render 的末尾调 drawFrame
        drawScreenBackground(g, leftPos, topPos, imageWidth, imageHeight);

        // 壁纸可能很亮，压一层暗色蒙版保证文字与物品看得清
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight,
                PhoneTheme.COLOR_SCRIM);

        for (Slot slot : slots) {
            if (!slot.isActive()) continue;
            // 格子的 x/y 是物品左上角，18×18 的框要往外扩 1px
            int sx = leftPos + slot.x - 1;
            int sy = topPos + slot.y - 1;
            g.fill(sx, sy, sx + SLOT_BACKPLATE, sy + SLOT_BACKPLATE, PhoneTheme.COLOR_SLOT_BG);
        }
    }

    /** 格子底板的边长：16px 物品 + 每边 1px 边框，与原版格子一致 */
    private static final int SLOT_BACKPLATE = 18;

    /**
     * 画任意尺寸的外壳与屏幕背景。
     *
     * 容器类界面（末影箱等）不受竖屏机身尺寸约束：格子要 9 列 ×18px，
     * 120px 宽的机身塞不下，硬塞就得牺牲功能。这类界面自己定尺寸，
     * 但仍复用同一套外壳与壁纸绘制，视觉上还是同一部手机。
     *
     * @param screenW 屏幕内区域宽（不含边框）
     * @param screenH 屏幕内区域高（不含边框）
     */
    public static void drawScreenBackground(GuiGraphics g, int phoneLeft, int phoneTop,
                                            int screenW, int screenH) {
        String wpName = NetworkHandler.WakeholderData.get();
        WallpaperStore.WallpaperEntry wp = WallpaperStore.findEntry(wpName);

        if (wp == null) {
            g.fill(phoneLeft, phoneTop,
                    phoneLeft + screenW, phoneTop + screenH,
                    PhoneTheme.COLOR_SCREEN_BG);
            return;
        }

        // 按 cover 方式等比缩放：覆盖整个屏幕，超出部分居中裁剪
        int texW = wp.imageWidth();
        int texH = wp.imageHeight();
        float s = Math.max((float) screenW / texW, (float) screenH / texH);
        int srcW = (int) (screenW / s);
        int srcH = (int) (screenH / s);

        // 参数顺序按 GuiGraphics 的 11 参重载：
        //   (贴图, x, y, 目标宽, 目标高, u, v, 源区宽, 源区高, 纹理宽, 纹理高)
        // 目标宽高在前、UV 在后，写反会导致目标矩形取到 srcX/srcY，
        // 而居中裁剪下二者必有一个为 0，壁纸就整个画不出来
        GuiUtil.drawTexture(g, wp.texture(),
                phoneLeft, phoneTop,
                screenW, screenH,
                (texW - srcW) / 2, (texH - srcH) / 2,
                srcW, srcH,
                texW, texH);
    }

    /**
     * 画外壳。
     *
     * 必须画在【最上层】—— 壁纸、页面内容、状态栏、导航栏全画完之后再画它。
     *
     * 1.6.12 之前是先画外壳再画壁纸，于是外壳贴图中间那一块必然被盖住，
     * 玩家只能做外圆角；屏幕四角永远是直角。改成最后画之后，贴图在屏幕
     * 区域里画的东西（内圆角、刘海、听筒之类）就都能盖在内容上了。
     *
     * 由此多出一条对贴图的硬要求：<b>中间那块必须是透明的</b>。不透明的话
     * 现在会把整个屏幕糊掉 —— 以前无所谓，因为反正会被壁纸盖住。
     *
     * 同一条也落在兜底路径上：没有贴图时只画四条边组成的那一圈，不能像
     * 以前那样填满整个机身。
     */
    public static void drawFrame(GuiGraphics g, int phoneLeft, int phoneTop,
                                 int screenW, int screenH) {
        final int b = PhoneTheme.PHONE_BORDER;
        final int fl = phoneLeft - b;
        final int ft = phoneTop - b;
        final int fw = screenW + b * 2;
        final int fh = screenH + b * 2;

        if (PhoneSkin.draw(g, PhoneSkin.Element.FRAME, fl, ft, fw, fh)) return;

        // 兜底：只画那一圈，理由见方法注释
        g.fill(fl, ft, fl + fw, ft + b, PhoneTheme.COLOR_FRAME);                 // 上
        g.fill(fl, ft + fh - b, fl + fw, ft + fh, PhoneTheme.COLOR_FRAME);       // 下
        g.fill(fl, ft + b, fl + b, ft + fh - b, PhoneTheme.COLOR_FRAME);         // 左
        g.fill(fl + fw - b, ft + b, fl + fw, ft + fh - b, PhoneTheme.COLOR_FRAME); // 右
        g.fill(fl, ft, fl + fw, ft + 2, PhoneTheme.COLOR_FRAME_HIGHLIGHT);       // 顶部高光
    }

    /** 状态栏没有贴图时的兜底底色（半透明黑，压在壁纸上仍看得清） */
    private static final int COLOR_STATUS_BAR_FALLBACK = PhoneTheme.COLOR_SCRIM;

    /** 画顶部状态栏：左侧信号、右侧时钟。背景可换肤 */
    public static void drawStatusBar(GuiGraphics g, Font font, int phoneLeft, int phoneTop) {
        PhoneSkin.drawOrFill(g, PhoneSkin.Element.STATUS_BAR,
                phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.STATUS_BAR_HEIGHT,
                COLOR_STATUS_BAR_FALLBACK);

        String time = LocalTime.now().format(TIME_FORMATTER);
        int tx = phoneLeft + PhoneTheme.PHONE_WIDTH - 6 - font.width(time);
        g.drawString(font, time, tx, phoneTop + 1, PhoneTheme.FONT_COLOR_STATUS, true);
        g.drawString(font, "●●●●", phoneLeft + 4, phoneTop + 1, PhoneTheme.FONT_COLOR_STATUS, true);
    }

    //  导航栏

    /** 导航栏上的三个虚拟按键 */
    public enum NavButton {
        /** 没点在导航栏上 */
        NONE,
        /** ◁ 返回上一层 */
        BACK,
        /** ○ 回主屏 */
        HOME,
        /** □ 多任务，暂未实现 */
        TASKS
    }

    /** 三个按键各占屏幕宽度的三分之一 */
    private static final NavButton[] NAV_ORDER =
            {NavButton.BACK, NavButton.HOME, NavButton.TASKS};

    private static final String[] NAV_GLYPHS = {"◁", "○", "□"};

    /** 与 NAV_ORDER 一一对应的可换肤图标 */
    private static final PhoneSkin.Element[] NAV_ICONS = {
            PhoneSkin.Element.NAV_BACK,
            PhoneSkin.Element.NAV_HOME,
            PhoneSkin.Element.NAV_TASKS
    };

    /**
     * 命中判定 —— 与绘制放在同一个类里，布局一改两边一起改。
     * 分开写迟早会出现"看得见点不到"或"点得到看不见"。
     */
    public static NavButton hitTestNavBar(double mouseX, double mouseY,
                                          int phoneLeft, int phoneTop) {
        int ny = phoneTop + PhoneTheme.PHONE_HEIGHT - PhoneTheme.NAV_BAR_HEIGHT;
        if (mouseY < ny || mouseY >= phoneTop + PhoneTheme.PHONE_HEIGHT) return NavButton.NONE;
        if (mouseX < phoneLeft || mouseX >= phoneLeft + PhoneTheme.PHONE_WIDTH) return NavButton.NONE;

        int tw = PhoneTheme.PHONE_WIDTH / 3;
        int idx = (int) ((mouseX - phoneLeft) / tw);
        // 宽度除不尽时最右侧可能算出 3，夹回最后一个按键
        if (idx >= NAV_ORDER.length) idx = NAV_ORDER.length - 1;
        return NAV_ORDER[idx];
    }

    /**
     * 画底部导航栏：三个虚拟按键，悬停时高亮。
     *
     * 高亮不只是好看：这三个键此前是纯装饰，玩家没有理由认为它们可点。
     * 鼠标移上去有反馈，才看得出是按钮。
     */
    public static void drawNavBar(GuiGraphics g, Font font, int phoneLeft, int phoneTop,
                                  int mouseX, int mouseY) {
        int ny = phoneTop + PhoneTheme.PHONE_HEIGHT - PhoneTheme.NAV_BAR_HEIGHT;
        PhoneSkin.drawOrFill(g, PhoneSkin.Element.NAV_BAR,
                phoneLeft, ny,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.NAV_BAR_HEIGHT,
                PhoneTheme.COLOR_NAV_BAR);

        NavButton hovered = hitTestNavBar(mouseX, mouseY, phoneLeft, phoneTop);

        int cy = ny + PhoneTheme.NAV_BAR_HEIGHT / 2 - font.lineHeight / 2;
        int tw = PhoneTheme.PHONE_WIDTH / 3;
        for (int i = 0; i < NAV_GLYPHS.length; i++) {
            boolean isHovered = hovered == NAV_ORDER[i];
            if (isHovered) {
                g.fill(phoneLeft + tw * i, ny,
                        phoneLeft + tw * (i + 1), phoneTop + PhoneTheme.PHONE_HEIGHT,
                        PhoneTheme.COLOR_ROW_HOVER);
            }
            // 悬停时整张贴图按倍数提亮。贴图改不了颜色，只能这么亮——
            // 用倍数而不是换成白色：资源包画的是什么颜色，亮起来还是那个颜色。
            // 设了就必须还原，否则后面画的东西跟着变亮
            if (isHovered) {
                float b = PhoneTheme.NAV_ICON_HOVER_BRIGHTNESS;
                g.setColor(b, b, b, 1f);
            }
            // 按键图标可换肤；没有贴图就画原来的字符符号
            boolean drawn = PhoneSkin.draw(g, NAV_ICONS[i],
                    phoneLeft + tw * i, ny, tw, PhoneTheme.NAV_BAR_HEIGHT);
            if (isHovered) g.setColor(1f, 1f, 1f, 1f);

            if (!drawn) {
                int bw = font.width(NAV_GLYPHS[i]);
                int bx = phoneLeft + tw * i + (tw - bw) / 2;
                g.drawString(font, NAV_GLYPHS[i], bx, cy,
                        isHovered ? PhoneTheme.FONT_COLOR_NAV_HOVER : PhoneTheme.FONT_COLOR_NAV, false);
            }
        }
    }
}
