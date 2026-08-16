package com.november.mcphone.core.client;

/**
 * 画在【壁纸】上的那些字是什么颜色 —— 随玩家在设置里选的预设变。
 *
 * ================================================================
 * 为什么是方法而不是 public static final int
 * ================================================================
 *
 * static final int 是【编译期常量】，javac 会把它的值直接烤进调用方的
 * class 文件：
 *
 *     ldc -8947849          ← 而不是 getstatic PhoneTheme.FONT_COLOR_TIMESTAMP
 *
 * 也就是说，那些常量在运行时根本改不动——改了值不重编整个项目就没有任何
 * 效果。这正是 PhoneStyle 当初做成接口而不是常量的理由，那时是为了附属
 * 模组；现在自家界面也要走进同一道门。
 *
 * 同一个坑还有个更隐蔽的版本：各页面里
 *
 *     private static final int COLOR_TITLE = PhoneTheme.FONT_COLOR_TITLE;
 *
 * 这种别名同样是编译期常量。只改这里、不改别名的话，表现是"有的页跟着变了、
 * 有的页没变"，而两边的代码看上去一模一样。
 *
 * ================================================================
 * 哪些字【不】在这里
 * ================================================================
 *
 * 画在手机自带构件上的字（导航栏、通知、未读角标、聊天气泡、输入栏、商店
 * 按钮、状态栏）不跟随预设，仍是 PhoneTheme 里钉死的常量。理由写在
 * PhoneTheme 的「画在构件上的字」那一节：底不变，字就不能变。
 *
 * ================================================================
 * 明显度阶梯
 * ================================================================
 *
 * 中性色不各写各的值，而是给一个 0～10 的【明显度】，在预设的两端之间插值。
 * 下面括号里是 WHITE 预设插出来的结果，与本功能落地前完全一致：
 *
 *     10  标题        (FFFFFF)        4  列表第二行的预览  (999999)
 *      7  正文        (CCCCCC)        3  次要信息          (888888)
 *      5  App 名      (AAAAAA)        2  时间戳            (777777)
 *      1  再暗一档    (666666)        0  点不动的动作      (555555)
 */
public final class FontPalette {

    private FontPalette() {}

    /**
     * 当前预设。
     *
     * 单独存一份而不是每次去问配置：配置在模组加载完之前读会抛异常，而渲染
     * 一帧要问上百次颜色——没有任何一次该冒这个险。由 ClientConfig 在配置
     * 加载与重载时推进来，在那之前就是默认值。
     */
    private static FontPreset current = FontPreset.WHITE;

    public static FontPreset current() { return current; }

    public static void set(FontPreset preset) {
        if (preset != null) current = preset;
    }

    // ==================== 中性色 ====================

    /** 标题、当前选中项。最显眼的一档 */
    public static int title() { return neutral(10); }

    /** 正文。绝大多数文字用这个 */
    public static int body() { return neutral(7); }

    /** 主屏图标下面那行 App 名 */
    public static int appName() { return neutral(5); }

    /** 列表第二行的内容预览 */
    public static int preview() { return neutral(4); }

    /** 次要信息：说明、作者、版本、分页页码 */
    public static int subtle() { return neutral(3); }

    /** 列表右侧那一列时间 */
    public static int timestamp() { return neutral(2); }

    /** 比 subtle 再暗一档：系统 App 的名字、提示的第二行 */
    public static int dim() { return neutral(1); }

    /** 点不动的动作：翻到头的翻页箭头、空输入时的按钮 */
    public static int muted() { return neutral(0); }

    // ==================== 语义色 ====================
    //
    // 这些靠色相表意，不参与明显度阶梯——把"危险"插成灰的没有意义。
    // 但深色字的预设配的是浅色底，亮色在白底上会淡得看不见，所以每个
    // 角色备了深浅两套，由预设自己决定用哪套。

    /** 可点的强调文字：右上角的「+」、当前生效的选项 */
    public static int link() { return pick(0xFF88CCFF, 0xFF15618F); }

    /** 确认类动作：保存、添加、打印 */
    public static int confirm() { return pick(0xFF66FF88, 0xFF15772C); }

    /** 等着再点一次确认，以及"同意"这类要看一眼再决定的动作 */
    public static int armed() { return pick(0xFFFFDD44, 0xFF7A5A00); }

    /** 会删东西的动作：删除、解除好友 */
    public static int danger() { return pick(0xFFFF8888, 0xFFA32B2B); }

    /** 危险动作已经就绪，下一次点击就真删了 */
    public static int dangerArmed() { return pick(0xFFFF5555, 0xFFC01414); }

    /** App 管理器里那个「✕ 卸载」 */
    public static int uninstall() { return pick(0xFFFF6666, 0xFFA83232); }

    /** 出错了、缺东西：商店的失败提示、缺前置模组的说明 */
    public static int notice() { return pick(0xFFFFAA44, 0xFF9C5200); }

    /** 价格 */
    public static int price() { return pick(0xFFFFD54F, 0xFF7A5E12); }

    /**
     * 说给屏幕前那个人听的那一行（时钟里的问候与关怀语）。
     *
     * 暖金色，比正文亮、比标题柔。它不是信息也不是警告，是这一页上唯一
     * 一句不为了让你知道什么而存在的话——用现成的哪个角色都不对：
     * notice 是警告，link 看着能点，subtle 又太像脚注。
     */
    public static int greeting() { return pick(0xFFFFD54F, 0xFF7A5E12); }

    // ==================== 算色 ====================

    /**
     * 按明显度在预设两端之间取色。
     *
     * 逐通道整数插值，不走浮点：WHITE 预设下 0.7f 这种系数算出来是
     * 118.99998 而不是 119，落到 CB 而不是 CC——一位之差没人看得出来，
     * 但"默认预设与从前逐字节相同"这条就不再成立，日后想用它做回归对比
     * 也就没了依据。
     *
     * @param prominence 0（最不显眼）～ 10（最显眼）
     */
    private static int neutral(int prominence) {
        FontPreset p = current;
        int strong = p.strong();
        int weak = p.weak();

        return 0xFF000000
                | (lerp((weak >> 16) & 0xFF, (strong >> 16) & 0xFF, prominence) << 16)
                | (lerp((weak >> 8) & 0xFF, (strong >> 8) & 0xFF, prominence) << 8)
                | lerp(weak & 0xFF, strong & 0xFF, prominence);
    }

    private static int lerp(int weak, int strong, int prominence) {
        return weak + (strong - weak) * prominence / 10;
    }

    /** 深色字的预设用后一个，其余用前一个 */
    private static int pick(int onDarkBackground, int onLightBackground) {
        return current.darkText() ? onLightBackground : onDarkBackground;
    }
}
