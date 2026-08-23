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

    // ==================== 画在【构件】上的字 ====================
    //
    // 手机上的字分两类，分界线是「底是什么」：
    //
    //   一类画在【壁纸】上——每一页都由 PhoneChassis.drawFrameAndWallpaper
    //   铺底，不只主屏。底是玩家自己选的，可深可浅。
    //
    //   另一类画在【手机自带的构件】上——导航栏、通知、未读角标、聊天气泡、
    //   输入栏、商店按钮。这些底是我们画的实心色，玩家换不了。
    //
    // 分开的理由：底不会变的地方，字也不能变。两者一起往同一个方向走，
    // 字就会陷进底里——导航栏那三个按钮直接消失，而且不报错，只是"点不着了"。
    //
    // 所以本节这些常量是【钉死】的，不随任何配色设置改变。会随设置改变的
    // 那一类不在这里，在 FontPalette。

    /** 状态栏时间/信号。底是 COLOR_SCRIM 压暗层 */
    public static final int FONT_COLOR_STATUS = 0xFFFFFFFF;

    /** 导航栏 ◀ ● ■ 三个符号。底是 COLOR_NAV_BAR */
    public static final int FONT_COLOR_NAV = 0xFF888888;

    /** 导航栏符号被鼠标悬着时 */
    public static final int FONT_COLOR_NAV_HOVER = 0xFFFFFFFF;

    /** 通知里的发信人名字与右上角条数。底是 COLOR_TOAST_BG */
    public static final int FONT_COLOR_TOAST_TITLE = 0xFFFFFFFF;

    /** 会话列表未读角标里的数字。底是 COLOR_UNREAD_BADGE 那块红 */
    public static final int FONT_COLOR_BADGE = 0xFFFFFFFF;

    /** 自己发的气泡里的字。底是 COLOR_CHAT_BUBBLE_SELF */
    public static final int FONT_COLOR_CHAT_SELF = 0xFFFFFFFF;

    /** 会话底部那个「发送」。底是 COLOR_CHAT_INPUT_BG */
    public static final int FONT_COLOR_CHAT_SEND = 0xFF66FF88;

    /** 「发送」被鼠标悬着时 */
    public static final int FONT_COLOR_CHAT_SEND_HOVER = 0xFFFFFFFF;

    /** 输入框空着，「发送」点了也不发 */
    public static final int FONT_COLOR_CHAT_SEND_OFF = 0xFF555555;

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

    // ==================== 各界面共用的角色色 ====================
    //
    // 下面这些此前是各界面自己写的字面量：分割线 0x44FFFFFF 在【十个】文件里
    // 各写一遍，行悬停底 0x33FFFFFF 五遍，"确认"绿 0xFF66FF88 分散在发送、
    // 保存、添加、打印四处。改一次配色要翻遍全项目，而且必漏。
    //
    // 【几个名字共用一个值是有意的】
    //
    // COLOR_DIVIDER、COLOR_CELL_HOVER 与上面的 COLOR_APP_PRESSED 眼下都是
    // 0x44FFFFFF，但它们是三件不同的东西。合并成一个名字的话，日后想把
    // 分割线调淡，主屏图标的按下反馈会跟着一起淡掉——而那不是任何人的本意。
    //
    // 名字按【角色】起，不按颜色起：值可以重合，角色不能混。

    // -- 覆盖层与填充 --

    /** 一像素分割线。标题与内容之间、列表分组之间 */
    public static final int COLOR_DIVIDER = 0x44FFFFFF;

    /** 更淡的分割线，用于同一块内容里的次级分组（关于页） */
    public static final int COLOR_DIVIDER_FAINT = 0x22FFFFFF;

    /** 列表行悬停时垫在下面的那层 */
    public static final int COLOR_ROW_HOVER = 0x33FFFFFF;

    /**
     * 悬停反馈里【重】的那一档：网格格子、独立按钮。
     *
     * 整行的列表项用淡一档的 {@link #COLOR_ROW_HOVER}——列表一行铺满整个
     * 宽度，同样的透明度盖上去比一个小格子显眼得多。
     */
    public static final int COLOR_HOVER_STRONG = 0x44FFFFFF;

    /** 悬停在"会删掉东西"的那一行上（App 管理器的卸载行） */
    public static final int COLOR_ROW_HOVER_DANGER = 0x44FF4444;

    /** 当前正生效的那一行（音乐播放器里正在放的曲子） */
    public static final int COLOR_ROW_ACTIVE = 0x2222AA44;

    /** 选中项的外圈（壁纸选择器里当前那张） */
    public static final int COLOR_SELECTION = 0x4488CCFF;

    /** 压暗一层：状态栏兜底、容器界面背景、相册格子底 */
    public static final int COLOR_SCRIM = 0x66000000;

    /** 压得更重的一层，用于要把注意力全收到前景的时候（相册看大图） */
    public static final int COLOR_OVERLAY = 0xCC000000;

    /** 容器界面里空槽位的底 */
    public static final int COLOR_SLOT_BG = 0x88000000;

    // -- 文字 --
    //
    // 画在【壁纸】上的那些字色不在这里，在 FontPalette —— 它们随玩家在
    // 「设置 → 字体颜色」里选的预设变，而 static final int 是编译期常量、
    // 运行时改不动。搬走的是这十七个角色：
    //
    //   标题 正文 App名 预览 次要 时间戳 更暗一档 点不动
    //   链接 确认 就绪 危险 危险就绪 卸载 提示 价格 问候
    //
    // 通知正文与对方气泡里的字看着也像"文字"，却留在本文件里：它们画在
    // 通知底与气泡底上，那两块底不会变。判据始终是「底是什么」，不是
    // 「这是不是一段字」。

    /** 通知里的正文。底是 COLOR_TOAST_BG */
    public static final int FONT_COLOR_TOAST = 0xFFBBBBBB;

    // -- 在线状态 --

    /** 在线的小圆点 */
    public static final int COLOR_ONLINE = 0xFF55DD55;

    /** 离线的小圆点 */
    public static final int COLOR_OFFLINE = 0xFF777777;

    // -- 聊天 --
    // 气泡底与输入栏底是【兜底】色，见 PhoneSkin.Element 的
    // CHAT_BUBBLE_SELF / CHAT_BUBBLE_PEER / CHAT_INPUT_BAR

    /** 自己发的气泡底 */
    public static final int COLOR_CHAT_BUBBLE_SELF = 0xFF2E6FDB;

    /** 对方发的气泡底 */
    public static final int COLOR_CHAT_BUBBLE_PEER = 0xFF3A3A4E;

    /** 对方气泡里的字。比纯白暗一点点，深色气泡上不刺眼 */
    public static final int FONT_COLOR_CHAT_PEER = 0xFFEEEEEE;

    /** 会话底部输入栏的底 */
    public static final int COLOR_CHAT_INPUT_BG = 0xFF26263A;

    /** 未读条数角标的底。兜底色，见 PhoneSkin.Element.UNREAD_BADGE */
    public static final int COLOR_UNREAD_BADGE = 0xFFDD3333;


    // -- 通知 --
    // 兜底色，见 PhoneSkin.Element.TOAST_BG

    /** 通知底 */
    public static final int COLOR_TOAST_BG = 0xFF1A1A2E;

    /** 通知边框 */
    public static final int COLOR_TOAST_BORDER = 0xFF0F3460;

    // -- 相机取景 --

    /** 取景框四角那四个折角 */
    public static final int COLOR_VIEWFINDER = 0xCCFFFFFF;

    /** 正中的准星 */
    public static final int COLOR_RETICLE = 0x99FFFFFF;

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
