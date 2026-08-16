package com.november.mcphone.core.client;


/**
 * =============================================================
 * MCphone GUI 主题配置中心
 * =============================================================
 *
 * 所有手机界面的视觉样式在这里统一管理，修改外观只需改这个文件。
 *
 * =============================================================
 * 【贴图不在这里，在 PhoneSkin】
 * =============================================================
 *
 * 可换肤元素的完整清单、各自的贴图路径与建议尺寸，全在
 * {@link PhoneSkin.Element} —— 一处定义，加新元素也只加那一处。
 * 面向玩家的同一份清单在 README 的「换肤（资源包）」一节。
 *
 * 本文件只管【没有贴图时用什么颜色画】。每个 COLOR_* 兜底色在
 * PhoneSkin.Element 对应项的注释里都被点名，两边是配对的。
 *
 * 这里【不】再抄一份贴图清单。1.2.7 把贴图从平铺的 textures/gui/ 挪进了
 * 按功能分的子目录，而这段注释没跟着改，于是它列了整整两个版本的
 * "status_bar.png 放进 textures/"——那个路径新老两条都不匹配，照它做出来的
 * 贴图一张都不会加载，而且不报错，只是"改了没反应"。清单有两份，就一定会
 * 有一份是过期的。
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

    /**
     * 拖动排序时，"松手就落这儿"的那个空槽提示色。
     *
     * 【兜底】色：放了 phone/drop_slot.png 就用贴图，见 PhoneSkin.Element.HOME_DROP_SLOT。
     * 比按下色更淡，因为它表达的是"这里空着等你放"，而不是"你正按着这个"。
     */
    public static final int COLOR_APP_DROP_SLOT = 0x33FFFFFF;

    /** 页码点：不是当前这一页的那些。兜底色，见 PhoneSkin.Element.HOME_PAGE_DOT */
    public static final int COLOR_PAGE_DOT = 0x55FFFFFF;

    /** 页码点：当前这一页。兜底色，见 PhoneSkin.Element.HOME_PAGE_DOT_ACTIVE */
    public static final int COLOR_PAGE_DOT_ACTIVE = 0xFFFFFFFF;

    /**
     * 拖着图标停在屏幕边上时，那条"再等一下就翻页"的提示条。
     *
     * 兜底色，见 PhoneSkin.Element.HOME_PAGE_EDGE。透明度由停留时长决定，
     * 这里写的是停满时的样子。
     */
    public static final int COLOR_PAGE_EDGE = 0x77FFFFFF;

    /** 底部导航栏背景色 */
    public static final int COLOR_NAV_BAR = 0xFF16213E;

    // -- 文字颜色 --
    /** 状态栏时间/文字颜色 */
    public static final int FONT_COLOR_STATUS = 0xFFFFFFFF;

    /** App 名称文字颜色 */
    public static final int FONT_COLOR_APP_NAME = 0xFFAAAAAA;

    /** 主标题文字颜色 */
    public static final int FONT_COLOR_TITLE = 0xFFFFFFFF;

    // -- 应用商店与详情页 --
    // 这几项都是【兜底】色：放了对应贴图时由 PhoneSkin 覆盖，见
    // PhoneSkin.Element 里的 STORE_BUTTON / STORE_BUTTON_DISABLED。

    /** 可点按钮的底色 */
    public static final int COLOR_BUTTON = 0xFF2E7D32;

    /** 鼠标悬停时的按钮底色 */
    public static final int COLOR_BUTTON_HOVER = 0xFF43A047;

    /** 点不动的按钮底色（已安装、买不起） */
    public static final int COLOR_BUTTON_DISABLED = 0xFF3A3A3A;

    /** 按钮上的文字 */
    public static final int FONT_COLOR_BUTTON = 0xFFFFFFFF;

    /** 点不动的按钮上的文字 */
    public static final int FONT_COLOR_BUTTON_DISABLED = 0xFF888888;

    /** 正文（App 简介之类） */
    public static final int FONT_COLOR_BODY = 0xFFCCCCCC;

    /** 次要信息（作者、版本、分页页码） */
    public static final int FONT_COLOR_SUBTLE = 0xFF888888;

    /** 价格 */
    public static final int FONT_COLOR_PRICE = 0xFFFFD54F;

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

    /**
     * 每页最多几行 App。
     *
     * 这是【上限】不是定值：实际行数还要看扣掉状态栏、导航栏、页码点之后剩多少
     * 高度，由 HomeLayout.rowsThatFit 算。写死成定值的话，谁把字体换大一号，
     * 最后一行就会压到页码点上，而那种错位没人会往"行数"上想。
     */
    public static final int APP_ROWS = 5;

    /** App 名称字体缩放，1.0=原大小 */
    public static final float APP_NAME_SCALE = 0.6f;

    // -- 主屏页码点 --

    /** 页码点那一条的高度，夹在图标区与导航栏之间 */
    public static final int PAGE_DOTS_HEIGHT = 8;

    /** 一个页码点的边长 */
    public static final int PAGE_DOT_SIZE = 3;

    /** 页码点之间的间隔 */
    public static final int PAGE_DOT_SPACING = 4;

    /**
     * 在空白处横着拖多远算一次翻页。
     *
     * 手机宽 120，取五分之一：再小的话，想拖个图标却按空了、手一抖就翻页；
     * 再大就得从屏幕这头划到那头，一页一页翻很累。
     */
    public static final int PAGE_SWIPE_THRESHOLD = 24;

    /** 翻页滑动动画时长（毫秒），0 表示直接切 */
    public static final int PAGE_SLIDE_MS = 160;

    /** 拖着图标停在左右这么宽的边条里，就会自动翻页 */
    public static final int PAGE_EDGE_WIDTH = 10;

    /**
     * 拖着图标在边条里停多久才翻页（毫秒）。
     *
     * 不能是"碰到就翻"：拖去最右那一列的路上必然会扫过右边条，一碰就翻的话
     * 玩家永远放不到最后一格。停顿是他表达"我真要去下一页"的方式。
     */
    public static final int PAGE_EDGE_DWELL_MS = 400;

    /**
     * 按住图标要移动多少像素才算"在拖动"，小于它松手仍然是一次点击。
     *
     * 没有这个阈值的话，按下时手稍微抖一下就变成了排序——玩家想开个 App，
     * 结果 App 换了位置还没打开。3 像素在 GUI 比例 2 下约等于 6 个物理像素，
     * 抖不到，有意拖的人也不会觉得"推不动"。
     */
    public static final int APP_DRAG_THRESHOLD = 3;

    // 贴图路径不在这里声明：可换肤元素统一由 PhoneSkin.Element 管理，
    // 一处定义文件名、尺寸建议与兜底行为，不会出现"声明了却没人用"的空头承诺。

    // ==================== 动画参数 ====================

    /** GUI 打开时的缩放动画时长（毫秒），0 表示无动画 */
    public static final int OPEN_ANIMATION_MS = 150;

    /** 手机在屏幕上的垂直偏移（负=上移），微调位置用 */
    public static final int SCREEN_Y_OFFSET = 0;
}
