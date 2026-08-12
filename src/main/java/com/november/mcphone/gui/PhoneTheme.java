package com.november.mcphone.gui;

import net.minecraft.resources.ResourceLocation;
import com.november.mcphone.MCphone;

/**
 * =============================================================
 * MCphone GUI 主题配置中心
 * =============================================================
 *
 * 所有手机界面的视觉样式在这里统一管理，方便你修改外观。
 *
 * 【如何使用】
 * - 改颜色：修改下面的 ARGB 颜色常量（格式: 0xAARRGGBB）
 * - 改大小：修改 PHONE_WIDTH / PHONE_HEIGHT / APP_ICON_SIZE 等
 * - 改字体：修改 FONT_COLOR_* 系列
 * - 改纹理：在 resources/assets/mcphone/textures/gui/ 下放置 PNG 图片，
 *   然后修改下面的 TEXTURE_* 路径常量
 *
 * 【贴图位置说明】
 * 如果你需要自定义纹理，请放置到：
 *   src/main/resources/assets/mcphone/textures/gui/
 *
 * 需要的贴图文件（可选，不放置则使用纯色绘制）：
 *   - phone_background.png : 手机主屏幕背景 (推荐 172x308 或等比缩放)
 *   - phone_frame.png      : 手机边框/外壳 (推荐 186x326，含边框)
 *   - app_icon_default.png : 默认 App 图标 (16x16 或 32x32)
 *   - status_bar_bg.png    : 顶部状态栏背景 (172x16)
 *
 * 贴图宽高会被适配到 PHONE_WIDTH/PHONE_HEIGHT 等对应尺寸，
 * 所以 PNG 只需要比例一致即可，不需要精确到像素。
 * =============================================================
 */
public final class PhoneTheme {

    private PhoneTheme() {} // 工具类，禁止实例化

    // ==================== 手机外观尺寸 ====================

    /** 手机屏幕内宽（不含边框），单位：像素 */
    public static final int PHONE_WIDTH = 172;

    /** 手机屏幕内高（不含边框），单位：像素 */
    public static final int PHONE_HEIGHT = 308;

    /** 手机边框厚度，单位：像素 */
    public static final int PHONE_BORDER = 7;

    /** 手机总宽度（含边框）= PHONE_WIDTH + PHONE_BORDER*2 */
    public static final int PHONE_TOTAL_WIDTH = PHONE_WIDTH + PHONE_BORDER * 2;

    /** 手机总高度（含边框）= PHONE_HEIGHT + PHONE_BORDER*2 */
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

    /** App 图标背景色（网格中的每个格子） */
    public static final int COLOR_APP_TILE = 0x00FFFFFF; // 全透明，不绘制图标背景

    /** App 图标按下时的覆盖色 */
    public static final int COLOR_APP_PRESSED = 0x44FFFFFF;

    /** 底部导航栏背景色 */
    public static final int COLOR_NAV_BAR = 0xFF16213E;

    // -- 文字颜色 --
    /** 状态栏时间/文字颜色 */
    public static final int FONT_COLOR_STATUS = 0xFFFFFFFF;

    /** App 名称文字颜色 */
    public static final int FONT_COLOR_APP_NAME = 0xFFCCCCCC;

    /** 主标题文字颜色 */
    public static final int FONT_COLOR_TITLE = 0xFFFFFFFF;

    // ==================== 布局参数 ====================

    /** 状态栏高度 */
    public static final int STATUS_BAR_HEIGHT = 16;

    /** 底部导航栏高度 */
    public static final int NAV_BAR_HEIGHT = 24;

    /** App 图标大小（正方形） */
    public static final int APP_ICON_SIZE = 32;

    /** App 网格水平间距 */
    public static final int APP_GRID_SPACING_X = 14;

    /** App 网格垂直间距 */
    public static final int APP_GRID_SPACING_Y = 14;

    /** App 网格左边距 */
    public static final int APP_GRID_PADDING_LEFT = 12;

    /** App 网格上边距（状态栏下方） */
    public static final int APP_GRID_PADDING_TOP = 8;

    /** 每行 App 数量 */
    public static final int APP_COLUMNS = 4;

    /** App 名称字体大小缩放，默认 1.0f */
    public static final float APP_NAME_SCALE = 0.7f;

    // ==================== 纹理路径（可选） ====================

    /**
     * 手机背景纹理（铺满 PHONE_WIDTH x PHONE_HEIGHT）
     * 放在: assets/mcphone/textures/gui/phone_background.png
     */
    public static final ResourceLocation TEXTURE_BACKGROUND =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "textures/gui/phone_background.png");

    /**
     * 默认 App 图标纹理
     * 放在: assets/mcphone/textures/gui/app_icon_default.png
     */
    public static final ResourceLocation TEXTURE_APP_DEFAULT =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "textures/gui/app_icon_default.png");

    /**
     * 状态栏背景纹理（可选）
     * 放在: assets/mcphone/textures/gui/status_bar_bg.png
     */
    public static final ResourceLocation TEXTURE_STATUS_BAR =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "textures/gui/status_bar_bg.png");

    // ==================== 动画参数 ====================

    /** GUI 打开时的缩放动画时长（毫秒），0 表示无动画 */
    public static final int OPEN_ANIMATION_MS = 200;

    /** 手机在屏幕上的垂直偏移（正数=向下），可用于微调位置 */
    public static final int SCREEN_Y_OFFSET = -10;
}
