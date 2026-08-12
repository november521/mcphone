package com.november.mcphone.gui;

import net.minecraft.resources.ResourceLocation;
import com.november.mcphone.MCphone;

/**
 * =============================================================
 * MCphone GUI 主题配置中心
 * =============================================================
 *
 * 所有手机界面的视觉样式在这里统一管理，修改外观只需改这个文件。
 *
 * =============================================================
 * 【贴图完整说明】
 * =============================================================
 *
 * 贴图放置目录：
 *   src/main/resources/assets/mcphone/textures/gui/
 *
 * ╔══════════════════════════════════════════════════════════╗
 * ║  文件名                  │ 必须尺寸  │ 用途             ║
 * ╠══════════════════════════════════════════════════════════╣
 * ║  phone_background.png   │ 120 × 200 │ 手机屏幕背景      ║
 * ║  app_icon_*.png         │  20 × 20  │ 各App图标(正方形) ║
 * ║  status_bar_bg.png      │ 120 × 10  │ 顶部状态栏背景    ║
 * ╚══════════════════════════════════════════════════════════╝
 *
 * 贴图格式要求：
 *   - 格式：PNG（必须，Minecraft 只认 PNG）
 *   - 色深：32位（含透明通道），背景透明部分用 Alpha=0
 *   - 尺寸：必须精确匹配上表，像素不对会导致拉伸/截断
 *   - 命名：全小写英文 + 下划线，如 app_icon_settings.png
 *
 * 如何制作贴图：
 *   1. 用任意绘图软件（Photoshop/Aseprite/GIMP）创建新画布
 *   2. 画布尺寸设为上表中的"必须尺寸"
 *   3. 画好内容，导出为 PNG-24/PNG-32（带透明通道）
 *   4. 将 PNG 文件放入上方贴图放置目录
 *
 * 不需要贴图也可以运行：
 *   所有贴图都是可选的。不放置贴图时，PhoneScreen 会用
 *   PhoneTheme 中定义的纯色来绘制（COLOR_* 系列常量），
 *   功能完全正常，只是外观是纯色块而非精美的纹理。
 *
 * =============================================================
 * 【尺寸对照表】
 * =============================================================
 *
 *   Minecraft 默认窗口（GUI比例=2）：427 × 240
 *   手机总尺寸（含边框）：       128 × 208
 *   手机占用屏幕比例：           ~30%宽 × ~87%高
 *
 *   屏幕内区域：120 × 200（9:15 纵横比，类似真实手机）
 *     ├─ 状态栏：120 × 10
 *     ├─ App网格区：4列 × N行
 *     └─ 导航栏：120 × 14
 *
 * =============================================================
 */
public final class PhoneTheme {

    private PhoneTheme() {} // 工具类，禁止实例化

    // ==================== 手机外观尺寸 ====================

    /** 手机屏幕内宽（不含边框），单位：像素 */
    public static final int PHONE_WIDTH = 120;

    /** 手机屏幕内高（不含边框），单位：像素 */
    public static final int PHONE_HEIGHT = 200;

    /** 手机边框厚度，单位：像素 */
    public static final int PHONE_BORDER = 4;

    /** 手机总宽度（含边框）= PHONE_WIDTH + PHONE_BORDER*2 = 128 */
    public static final int PHONE_TOTAL_WIDTH = PHONE_WIDTH + PHONE_BORDER * 2;

    /** 手机总高度（含边框）= PHONE_HEIGHT + PHONE_BORDER*2 = 208 */
    public static final int PHONE_TOTAL_HEIGHT = PHONE_HEIGHT + PHONE_BORDER * 2;

    // ==================== 颜色方案 ====================

    // -- 手机外壳颜色 --
    /** 手机外壳/边框颜色 */
    public static final int COLOR_FRAME = 0xFF2C2C2C;

    /** 手机外壳高光色（顶部边缘） */
    public static final int COLOR_FRAME_HIGHLIGHT = 0xFF4A4A4A;

    // -- 屏幕内部颜色 --
    /** 主屏幕背景色 */
    public static final int COLOR_SCREEN_BG = 0xFF1A1A2E;

    /** 状态栏背景色 */
    public static final int COLOR_STATUS_BAR = 0xFF0F3460;

    /** App 图标按下时的覆盖色 */
    public static final int COLOR_APP_PRESSED = 0x44FFFFFF;

    /** 底部导航栏背景色 */
    public static final int COLOR_NAV_BAR = 0xFF16213E;

    // -- 文字颜色 --
    /** 状态栏时间/文字颜色 */
    public static final int FONT_COLOR_STATUS = 0xFFFFFFFF;

    /** App 名称文字颜色 */
    public static final int FONT_COLOR_APP_NAME = 0xFFAAAAAA;

    /** 主标题文字颜色 */
    public static final int FONT_COLOR_TITLE = 0xFFFFFFFF;

    // ==================== 布局参数 ====================

    /** 状态栏高度 */
    public static final int STATUS_BAR_HEIGHT = 10;

    /** 底部导航栏高度 */
    public static final int NAV_BAR_HEIGHT = 14;

    /** App 图标大小（正方形），贴图也必须为此尺寸 */
    public static final int APP_ICON_SIZE = 20;

    /** App 网格水平间距 */
    public static final int APP_GRID_SPACING_X = 8;

    /** App 网格垂直间距 */
    public static final int APP_GRID_SPACING_Y = 6;

    /** App 网格左边距 */
    public static final int APP_GRID_PADDING_LEFT = 8;

    /** App 网格上边距（状态栏下方） */
    public static final int APP_GRID_PADDING_TOP = 6;

    /** 每行 App 数量 */
    public static final int APP_COLUMNS = 4;

    /** App 名称字体缩放，1.0=原大小 */
    public static final float APP_NAME_SCALE = 0.6f;

    // ==================== 纹理路径（可选） ====================

    /**
     * 手机背景纹理（120×200 PNG）
     * 放置路径: assets/mcphone/textures/gui/phone_background.png
     */
    public static final ResourceLocation TEXTURE_BACKGROUND =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "textures/gui/phone_background.png");

    /**
     * 默认 App 图标纹理（20×20 PNG）
     * 放置路径: assets/mcphone/textures/gui/app_icon_default.png
     */
    public static final ResourceLocation TEXTURE_APP_DEFAULT =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "textures/gui/app_icon_default.png");

    /**
     * 状态栏背景纹理（120×10 PNG）
     * 放置路径: assets/mcphone/textures/gui/status_bar_bg.png
     */
    public static final ResourceLocation TEXTURE_STATUS_BAR =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "textures/gui/status_bar_bg.png");

    // ==================== 动画参数 ====================

    /** GUI 打开时的缩放动画时长（毫秒），0 表示无动画 */
    public static final int OPEN_ANIMATION_MS = 150;

    /** 手机在屏幕上的垂直偏移（负=上移），微调位置用 */
    public static final int SCREEN_Y_OFFSET = 0;
}
