package com.november.mcphone.core.client;

/**
 * 手机界面的尺寸与兜底颜色。
 * 贴图清单不在这里：可换肤元素的路径与建议尺寸全在 {@link PhoneSkin.Element}，
 * 本文件只管没有贴图时用什么颜色画，每个 COLOR_* 与那边的对应项配对。
 * 屏幕内区域 120×200（状态栏 10 / 导航栏 14）是所有页面排版的基准；含边框整机 136×216。
 */
public final class PhoneTheme {

    private PhoneTheme() {}

    /** 手机屏幕内宽（不含边框），单位：像素 */
    public static final int PHONE_WIDTH = 120;

    /** 手机屏幕内高（不含边框），单位：像素 */
    public static final int PHONE_HEIGHT = 200;

    /** 边框厚度。8 能撑到 11px 圆角；再厚整机高会在 240 的最小逻辑高度下顶到屏幕边 */
    public static final int PHONE_BORDER = 8;

    public static final int PHONE_TOTAL_WIDTH = PHONE_WIDTH + PHONE_BORDER * 2;

    public static final int PHONE_TOTAL_HEIGHT = PHONE_HEIGHT + PHONE_BORDER * 2;

    public static final int COLOR_FRAME = 0xFF2C2C2C;

    /** 手机外壳高光色（顶部边缘） */
    public static final int COLOR_FRAME_HIGHLIGHT = 0xFF4A4A4A;

    /**
     * 没有壁纸时的屏幕底色。在线圆点描边（PlayerAvatar）与浏览器面板底刻意同色；
     * 浏览器面板贴图见 PhoneSkin.Element.BROWSER_PANEL。
     */
    public static final int COLOR_SCREEN_BG = 0xFF0C1445;

    /** 状态栏背景色，贴图见 PhoneSkin.Element.STATUS_BAR */
    public static final int COLOR_STATUS_BAR = 0xFF0F3460;

    /** App 图标按下时的覆盖色 */
    public static final int COLOR_APP_PRESSED = 0x44FFFFFF;

    /** 拖动排序的空槽提示色，比按下色更淡。兜底色，贴图见 PhoneSkin.Element.HOME_DROP_SLOT */
    public static final int COLOR_APP_DROP_SLOT = 0x33FFFFFF;

    /** 页码点：不是当前这一页的那些。兜底色，见 PhoneSkin.Element.HOME_PAGE_DOT */
    public static final int COLOR_PAGE_DOT = 0x55FFFFFF;

    /** 页码点：当前这一页。兜底色，见 PhoneSkin.Element.HOME_PAGE_DOT_ACTIVE */
    public static final int COLOR_PAGE_DOT_ACTIVE = 0xFFFFFFFF;

    /** 拖图标停在屏幕边上的翻页提示条，停满时的透明度。兜底色，贴图见 PhoneSkin.Element.HOME_PAGE_EDGE */
    public static final int COLOR_PAGE_EDGE = 0x77FFFFFF;

    /** 底部导航栏背景色，贴图见 PhoneSkin.Element.NAV_BAR */
    public static final int COLOR_NAV_BAR = 0xFF16213E;

    // 以下 FONT_COLOR_* 画在手机自带构件（导航栏、通知、气泡等）的实心底上，
    // 底不变字也不能变——钉死，不随设置走；随「字体颜色」设置变的在 FontPalette。

    /** 状态栏时间/信号。底是 COLOR_SCRIM 压暗层 */
    public static final int FONT_COLOR_STATUS = 0xFFFFFFFF;

    /** 导航栏 ◀ ● ■ 三个符号。底是 COLOR_NAV_BAR */
    public static final int FONT_COLOR_NAV = 0xFF888888;

    /** 导航栏符号被鼠标悬着时 */
    public static final int FONT_COLOR_NAV_HOVER = 0xFFFFFFFF;

    /**
     * 导航栏三个键【用贴图时】悬停提亮的倍数。字符符号走上面那个颜色，贴图改不了颜色只能整张乘一下。
     * 1.8 让自带的 #828282 亮到 #EAEAEA，与字符版 #888→#FFF 的观感相当；再高会把浅色贴图压成一片白。
     */
    public static final float NAV_ICON_HOVER_BRIGHTNESS = 1.8f;

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

    // 兜底色，贴图见 PhoneSkin.Element.STORE_BUTTON / STORE_BUTTON_DISABLED

    public static final int COLOR_BUTTON = 0xFF2E7D32;

    public static final int COLOR_BUTTON_HOVER = 0xFF43A047;

    /** 点不动的按钮底色（已安装、买不起） */
    public static final int COLOR_BUTTON_DISABLED = 0xFF3A3A3A;

    public static final int FONT_COLOR_BUTTON = 0xFFFFFFFF;

    public static final int FONT_COLOR_BUTTON_DISABLED = 0xFF888888;

    // 各界面共用的角色色。名字按【角色】起、不按颜色起：几个名字共用一个值是有意的，
    // 它们是不同的东西，日后要能各自调整，不能合并成一个名字。

    /** 一像素分割线。标题与内容之间、列表分组之间 */
    public static final int COLOR_DIVIDER = 0x44FFFFFF;

    /** 更淡的分割线，用于同一块内容里的次级分组（关于页） */
    public static final int COLOR_DIVIDER_FAINT = 0x22FFFFFF;

    /** 列表行悬停时垫在下面的那层 */
    public static final int COLOR_ROW_HOVER = 0x33FFFFFF;

    /** 悬停反馈里重的那一档：网格格子、独立按钮。整行的列表项用淡一档的 {@link #COLOR_ROW_HOVER} */
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

    // 画在【壁纸】上的字色不在这里，在 FontPalette——它们随设置变，编译期常量改不动。
    // 通知正文与对方气泡的字留在本文件：它们的底不变。判据是「底是什么」。

    /** 通知里的正文。底是 COLOR_TOAST_BG */
    public static final int FONT_COLOR_TOAST = 0xFFBBBBBB;

    /** 在线的小圆点 */
    public static final int COLOR_ONLINE = 0xFF55DD55;

    /** 离线的小圆点 */
    public static final int COLOR_OFFLINE = 0xFF777777;

    // 气泡底与输入栏底是兜底色，贴图见 PhoneSkin.Element 的 CHAT_BUBBLE_SELF / CHAT_BUBBLE_PEER / CHAT_INPUT_BAR

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

    /** 进度条已播过的那一段 */
    public static final int COLOR_MUSIC_PROGRESS = 0xFF55DD88;

    /** 进度条没播到的那一段。比已播的暗一档，两段要能一眼分开 */
    public static final int COLOR_MUSIC_PROGRESS_BG = 0x44FFFFFF;

    /** 通知底。兜底色，贴图见 PhoneSkin.Element.TOAST_BG */
    public static final int COLOR_TOAST_BG = 0xFF1A1A2E;

    /** 通知边框 */
    public static final int COLOR_TOAST_BORDER = 0xFF0F3460;

    /** 取景框四角那四个折角 */
    /** 书架上那本没有图标的书。皮革棕，与「阅读」App 图标同一个底色 */
    public static final int COLOR_BOOK_SPINE = 0xFF8A5A2B;

    /**
     * 书架顶上那条搜索栏的底。
     *
     * 与会话页输入栏 {@link #COLOR_CHAT_INPUT_BG} 眼下是同一个值，但单列一条：
     * 它们是两个界面里的两样东西，将来谁要调都不该顺手把另一个也改了。
     */
    public static final int COLOR_SEARCH_BAR = 0xFF26263A;

    /**
     * 阅读 App 底部「书架 / 书城」当前那一页的底。
     *
     * 比搜索栏亮一档：它要从壁纸上"浮"出来说明自己是当前页，而搜索栏只是个
     * 输入框的底，沉下去更合适。
     */
    public static final int COLOR_READER_TAB = 0xFF3A3A52;

    public static final int COLOR_VIEWFINDER = 0xCCFFFFFF;

    /** 正中的准星 */
    public static final int COLOR_RETICLE = 0x99FFFFFF;

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

    /** 每页最多几行 App，是上限不是定值：实际行数由 HomeLayout.rowsThatFit 按剩余高度算 */
    public static final int APP_ROWS = 5;

    /** App 名称字体缩放，1.0=原大小 */
    public static final float APP_NAME_SCALE = 0.6f;

    /** 页码点那一条的高度，夹在图标区与导航栏之间 */
    public static final int PAGE_DOTS_HEIGHT = 8;

    /** 一个页码点的边长 */
    public static final int PAGE_DOT_SIZE = 3;

    /** 页码点之间的间隔 */
    public static final int PAGE_DOT_SPACING = 4;

    /** 在空白处横着拖多远（像素）算一次翻页 */
    public static final int PAGE_SWIPE_THRESHOLD = 24;

    /** 翻页滑动动画时长（毫秒），0 表示直接切 */
    public static final int PAGE_SLIDE_MS = 160;

    /** 拖着图标停在左右这么宽的边条里，就会自动翻页 */
    public static final int PAGE_EDGE_WIDTH = 10;

    /** 拖着图标在边条里停多久（毫秒）才翻页。不能碰到就翻：拖去最右一列的路上必然扫过边条 */
    public static final int PAGE_EDGE_DWELL_MS = 400;

    /** 按住图标移动超过这么多像素才算拖动，小于它松手仍是一次点击 */
    public static final int APP_DRAG_THRESHOLD = 3;

    /** GUI 打开时的缩放动画时长（毫秒），0 表示无动画 */
    public static final int OPEN_ANIMATION_MS = 150;

    /** 手机在屏幕上的垂直偏移（负=上移），微调位置用 */
    public static final int SCREEN_Y_OFFSET = 0;
}
