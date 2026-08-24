package com.november.mcphone.core.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.store.AppInfo;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.core.PhoneLocation;
import com.november.mcphone.feature.chat.client.ChatAddContact;
import com.november.mcphone.feature.chat.client.ChatConversation;
import com.november.mcphone.feature.chat.client.ChatList;
import com.november.mcphone.feature.gallery.client.Gallery;
import com.november.mcphone.feature.music.client.MusicPage;
import com.november.mcphone.feature.notes.client.NoteEditor;
import com.november.mcphone.feature.notes.client.NotesList;
import com.november.mcphone.feature.settings.client.AboutPage;
import com.november.mcphone.feature.settings.client.AppManagerPage;
import com.november.mcphone.feature.settings.client.DeviceNameEditor;
import com.november.mcphone.feature.settings.client.FontColorPicker;
import com.november.mcphone.feature.settings.client.WallpaperPicker;
import com.november.mcphone.feature.settings.client.WallpaperStore;
import com.november.mcphone.feature.store.client.AppDetail;
import com.november.mcphone.feature.store.client.AppStore;
import com.november.mcphone.feature.store.client.CompanionApps;
import com.november.mcphone.feature.clock.client.ClockPage;
import com.november.mcphone.feature.weather.client.WeatherPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 手机主屏幕 GUI。
 *
 * 此 Screen 管理手机的导航状态：
 * - MAIN：App 图标网格（桌面）
 * - SETTINGS：设置列表
 * - WALLPAPER_PICKER：壁纸选择
 */
public final class PhoneScreen extends Screen {

    /** 手机导航模式 */
    public enum Mode { MAIN, SETTINGS, WALLPAPER_PICKER, FONT_COLOR_PICKER, APP_MANAGER, MUSIC_PLAYER, APP_STORE, APP_DETAIL, COMPANION_APPS, ADDON_PAGE, ABOUT, GALLERY, DEVICE_NAME, CHAT, CHAT_ADD_CONTACT, CHAT_CONVERSATION, NOTES, NOTE_EDIT, CLOCK, WEATHER }


    // ---- 打开动画 ----
    private final long openTimeMs;
    private boolean animationDone;

    // ---- 模式 ----
    private Mode mode = Mode.MAIN;
    private final WallpaperPicker wallpaperPicker = new WallpaperPicker();
    private final FontColorPicker fontColorPicker = new FontColorPicker();

    // ---- 设置列表项 ----
    /**
     * @param value 右侧显示的当前值，null 表示画 ">" 箭头。
     *              用 Supplier 而非定值：列表只构建一次，
     *              而设备名是会变的，每帧现取才不会显示过期的名字。
     */
    private static final record SettingItem(String label, Runnable action,
                                            java.util.function.Supplier<String> value) {
        SettingItem(String label, Runnable action) { this(label, action, null); }
    }
    private final List<SettingItem> settingItems = new ArrayList<>();
    private int hoveredSettingIdx = -1;

    // ---- App 管理器 ----
    private final AppManagerPage appManagerPage = new AppManagerPage();

    // ---- 音乐播放器 ----
    private final MusicPage musicPage = new MusicPage();

    // ---- 应用商店 ----
    private final AppStore appStore = new AppStore();
    private final AppDetail appDetail = new AppDetail();
    private final CompanionApps companionApps = new CompanionApps();

    /**
     * 附属 App 当前打开的那一页。为 null 表示没有。
     *
     * 它是唯一一处"界面由别人写"的地方，所以每一个回调都得兜住 Throwable——
     * 附属的页面抛异常只该让这一页关掉，不该拖垮整个手机界面。
     */
    private IPhonePage addonPage = null;

    // ---- 相册 ----
    private final Gallery gallery = new Gallery();

    // ---- 设备名称 ----
    private final DeviceNameEditor deviceNameEditor = new DeviceNameEditor();

    // ---- 聊天 ----
    private final ChatList chatList = new ChatList();
    private final ChatAddContact chatAddContact = new ChatAddContact();
    private final ChatConversation chatConversation = new ChatConversation();

    // ---- 记事本 ----
    private final NotesList notesList = new NotesList();
    private final NoteEditor noteEditor = new NoteEditor();

    /**
     * 待打开的会话对端。
     *
     * navigateTo 只认模式、不带参数，而进入会话必须知道是跟谁聊——
     * 用一个字段把 peer 递进去，别的界面都不需要这种东西，
     * 所以不值得为它改 navigateTo 的签名。
     */
    private UUID pendingConversationPeer;

    /**
     * 手机在玩家身上的什么位置。
     *
     * 设备名要写回【这一部】手机——玩家身上可能不止一部，右键开的、
     * 快捷键从背包翻出来的、挂在饰品槽里的，改错了就改到别人头上。
     */
    private final PhoneLocation location;

    // ---- 主屏幕 hover ----
    /** 鼠标停在第几个 App。是【全局】下标，不是当前页里的第几格 */
    private int hoveredAppIndex = -1;

    /**
     * 主屏当前停在第几页。
     *
     * 装的 App 变少（卸载、换存档）时它可能指到不存在的页，所以每次画之前都
     * 重新夹一次，而不是在每个改动安装状态的地方各夹一遍——那种写法迟早漏一处，
     * 表现是主屏一片空白、玩家以为 App 全没了。
     */
    private int homePage = 0;

    /** 在空白处按下了吗 —— 横着拖它就是翻页 */
    private boolean pressedBlank;

    /** 翻页动画：从哪一页滑过来的 */
    private int slideFromPage;

    /** 翻页动画的起始时刻，0 表示没在滑 */
    private long pageSlideStartMs;

    // ---- 主屏拖动排序 ----
    //
    // 按下【不】立即开 App：这一下可能是要把图标拖去别的格子。按下只记下是哪一格，
    // 真正开 App 推迟到 mouseReleased，位移没超过阈值才算一次点击。
    // 这是"能拖"必然要付的代价——按下的那一刻还看不出玩家想干什么。

    /** 按下时命中的图标下标，-1 表示这次按下没落在图标上 */
    private int pressedAppIndex = -1;

    /** 按下点的坐标（已撤掉开场动画的缩放，见 unscaledX），用来量挪了多远 */
    private double pressX, pressY;

    /** 位移超过 {@link PhoneTheme#APP_DRAG_THRESHOLD} 之后才为 true */
    private boolean draggingApp;

    /** 拖动中鼠标所在的坐标（同上，已撤掉缩放），浮起的那张图标画在这儿 */
    private double dragX, dragY;

    /** 拖动中松手会落到第几格 */
    private int dragTargetIndex = -1;

    /** 拖着图标正停在哪条边上：-1 左、1 右、0 不在边上 */
    private int edgeDwellSide;

    /** 停进边条的时刻，用来算停够了没有 */
    private long edgeDwellStartMs;

    // ---- 布局缓存 ----
    private int phoneLeft, phoneTop;
    private int gridStartX, gridStartY;
    private boolean layoutDirty = true;
    private long nowMs;

    public PhoneScreen(PhoneLocation location) {
        super(Component.translatable("mcphone.gui.home"));
        this.location = location;
        this.openTimeMs = System.currentTimeMillis();
        this.animationDone = PhoneTheme.OPEN_ANIMATION_MS <= 0;
    }

    //  导航

    public void navigateTo(Mode target) {
        if (this.mode == target) return;

        // 离开商店时清掉上次的列表与提示，下次进入重新拉取
        // 进详情页不算离开商店：reset 会清掉页码与列表，从详情页退回来
        // 时玩家会莫名其妙回到第一页
        if (this.mode == Mode.APP_STORE
                && target != Mode.APP_STORE && target != Mode.APP_DETAIL
                && target != Mode.COMPANION_APPS) {
            appStore.reset();
        }

        // 联动页进出都要动作：进入时重扫一遍（模组装没装只在这时问一次就够），
        // 离开时清掉页码，下次进来从第一页开始
        // 离开附属页面就把它关掉：它可能占着资源，而玩家已经走了。
        // 这里只管页面之间的切换（◁、点别的 App）。ESC 关机、被别的界面顶掉、
        // 断线这几条根本不经过 navigateTo，它们由 removed() 兜住——两处合起来
        // 才凑齐 IPhonePage.onClose() 那句"一定会被调用"
        if (this.mode == Mode.ADDON_PAGE && target != Mode.ADDON_PAGE) closeAddonPage();

        if (this.mode == Mode.COMPANION_APPS) companionApps.reset();
        if (target == Mode.COMPANION_APPS) companionApps.refresh();

        // 相册进出都要动作：进入时重扫目录，离开时释放缩略图贴图
        if (this.mode == Mode.GALLERY) gallery.close();
        if (target == Mode.GALLERY) gallery.open();

        // 进入命名界面时把当前设备名填进输入框
        if (this.mode == Mode.DEVICE_NAME) deviceNameEditor.close();
        if (target == Mode.DEVICE_NAME) deviceNameEditor.open(location);

        // 会话列表离开即停止定时刷新，不在后台空转
        if (this.mode == Mode.CHAT) chatList.close();
        if (target == Mode.CHAT) chatList.open();

        if (this.mode == Mode.CHAT_ADD_CONTACT) chatAddContact.close();
        if (target == Mode.CHAT_ADD_CONTACT) chatAddContact.open();

        // 进入会话即拉取历史消息，离开即释放——聊天记录不留在内存里空占着
        if (this.mode == Mode.CHAT_CONVERSATION) chatConversation.close();
        if (target == Mode.CHAT_CONVERSATION) chatConversation.open(pendingConversationPeer);

        // 进入列表即拉一次笔记；离开编辑界面即释放当前那条的全文
        // 进换壁纸页先重扫一次目录：玩家刚拷进去的图得立刻能选，
        // 不能要求他为换张壁纸重启游戏
        if (target == Mode.WALLPAPER_PICKER) WallpaperStore.refresh();

        if (this.mode == Mode.NOTES) notesList.close();
        if (target == Mode.NOTES) notesList.open();
        if (target == Mode.APP_MANAGER) appManagerPage.open();

        // 进音乐页要重扫一次曲库：玩家刚往文件夹里丢的歌得立刻在列表里。
        // 离开【不停音乐】——退出播放器界面歌还在放，那是手机的常识
        if (this.mode == Mode.MUSIC_PLAYER) musicPage.close();
        if (target == Mode.MUSIC_PLAYER) musicPage.open();

        if (this.mode == Mode.NOTE_EDIT) noteEditor.close();

        if (this.mode == Mode.FONT_COLOR_PICKER) fontColorPicker.close();

        // 时钟靠"连续多少帧没动"判断时间停没停，离开时清掉，
        // 免得下次进来带着上次的判断先闪一下"时间已停止"
        if (this.mode == Mode.CLOCK) ClockPage.reset();

        this.mode = target;
        this.hoveredSettingIdx = -1;
    }

    public void back() {
        navigateTo(Mode.MAIN);
    }

    /**
     * 正开着与这个人的会话吗。
     *
     * 收到消息时据此决定要不要弹通知：消息已经在眼前了就别再打扰。
     * 开着别的会话、或停在手机的其他界面，都照常提醒。
     */
    public boolean isViewingConversation(UUID peer) {
        return mode == Mode.CHAT_CONVERSATION && chatConversation.isViewing(peer);
    }

    //  附属 App 的页面
    //
    // 这里是整个界面里唯一一处"代码由别人写"的地方。每一个进出口都要兜住
    // Throwable：附属的页面抛异常只该让这一页被关掉并记一条日志，不该拖垮
    // 手机界面，更不该崩游戏。
    //
    // 兜 Throwable 而不是 Exception 是刻意的：附属最常见的死法是引用了没装的
    // 模组里的类，那抛的是 NoClassDefFoundError——属于 Error，Exception 接不住。

    /**
     * 点开一个 App。
     *
     * 先问它要不要一页画在手机里（openPage）；不要就走老路调 onPress()，
     * 由它自己 setScreen 跳出去。1.2.13 之前只有后者。
     */
    private void launchApp(IPhoneApp app) {
        IPhonePage page;
        try {
            page = app.openPage();
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] App {} 的 openPage() 抛异常，改用 onPress()",
                    app.getId(), t);
            page = null;
        }

        if (page != null) {
            openAddonPage(page);
            return;
        }

        try {
            app.onPress();
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] App {} 的 onPress() 抛异常", app.getId(), t);
        }
    }

    /** 打开一页附属界面 */
    private void openAddonPage(IPhonePage page) {
        closeAddonPage();
        addonPage = page;
        try {
            page.onOpen();
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 附属页面 {} 的 onOpen() 抛异常",
                    page.getClass().getName(), t);
        }
        navigateTo(Mode.ADDON_PAGE);
    }

    /**
     * 关掉当前这一页。
     *
     * 先把字段清空再回调，不是反过来：onClose() 里要是又去开一页（附属完全
     * 可能这么写），后清空会把新开的那页一起抹掉，表现成"点了没反应"。
     */
    private void closeAddonPage() {
        IPhonePage page = addonPage;
        addonPage = null;
        if (page == null) return;
        try {
            page.onClose();
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 附属页面 {} 的 onClose() 抛异常",
                    page.getClass().getName(), t);
        }
    }

    /**
     * 调一次页面的回调，兜住异常。
     *
     * 抛异常的页面会被【当场关掉并退回主屏】，而不是留着每帧再抛一次——那样
     * 日志一秒钟能刷几千行，真正的第一条错误反而被冲掉了。
     *
     * @return 页面说它处理了没有；出异常时算没处理
     */
    private boolean callPage(java.util.function.Predicate<IPhonePage> call) {
        IPhonePage page = addonPage;
        if (page == null) return false;
        try {
            return call.test(page);
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 附属页面 {} 抛异常，已关闭该页",
                    page.getClass().getName(), t);
            closeAddonPage();
            navigateTo(Mode.MAIN);
            return false;
        }
    }

    /** 这一页有没有输入框。问不出来就当没有——那是更安全的一侧 */
    private boolean pageCapturesKeyboard() {
        IPhonePage page = addonPage;
        if (page == null) return false;
        try {
            return page.capturesKeyboard();
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 附属页面 {} 的 capturesKeyboard() 抛异常",
                    page.getClass().getName(), t);
            return false;
        }
    }

    /**
     * 画附属那一页。
     *
     * 给它的是【内容区】——状态栏与导航栏已经扣掉，附属不需要知道那两条多高，
     * 我们改了它们的高度它也不受影响。
     */
    private void renderAddonPage(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        IPhonePage page = addonPage;
        if (page == null) {
            navigateTo(Mode.MAIN);
            return;
        }

        int contentY = phoneTop + PhoneTheme.STATUS_BAR_HEIGHT;
        int contentH = PhoneTheme.PHONE_HEIGHT
                - PhoneTheme.STATUS_BAR_HEIGHT - PhoneTheme.NAV_BAR_HEIGHT;

        PhoneCanvas canvas = new PhoneCanvas(g, font,
                phoneLeft, contentY, PhoneTheme.PHONE_WIDTH, contentH,
                mouseX, mouseY, partialTick, ThemeStyle.INSTANCE);

        try {
            page.render(canvas);
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 附属页面 {} 渲染抛异常，已关闭该页",
                    page.getClass().getName(), t);
            closeAddonPage();
            navigateTo(Mode.MAIN);
        }
    }

    /**
     * 返回上一层。
     *
     * 只有导航栏的 ◁ 走这里。ESC 从 1.4.2 起不再退层，它一下直接关机，
     * 见 {@link #keyPressed}。
     *
     * @return 真的退了一层才返回 true；已在主屏返回 false。
     *         ◁ 不看这个返回值——主屏上按它什么都不做
     */
    private boolean goBackOneLevel() {
        // 相册的单张查看是相册内的一层，先退回缩略图网格
        if (mode == Mode.GALLERY && gallery.backToGrid()) return true;

        // 命名界面退回设置列表，而非直接回主屏
        if (mode == Mode.DEVICE_NAME) {
            navigateTo(Mode.SETTINGS);
            return true;
        }

        // 加联系人与具体会话都是聊天里的一层，退回会话列表
        if (mode == Mode.CHAT_ADD_CONTACT || mode == Mode.CHAT_CONVERSATION) {
            navigateTo(Mode.CHAT);
            return true;
        }

        // 编辑一条笔记是记事本里的一层，退回笔记列表
        if (mode == Mode.NOTE_EDIT) {
            navigateTo(Mode.NOTES);
            return true;
        }

        // 附属页面：先问它自己要不要拦，不拦就退回主屏
        if (mode == Mode.ADDON_PAGE) {
            if (callPage(IPhonePage::onBack)) return true;
            navigateTo(Mode.MAIN);
            return true;
        }

        // App 详情与联动 App 都是商店里的一层，退回商店首页
        if (mode == Mode.APP_DETAIL || mode == Mode.COMPANION_APPS) {
            navigateTo(Mode.APP_STORE);
            return true;
        }

        // 关于是设置里的一层，退回设置列表而非主屏
        if (mode == Mode.ABOUT) {
            navigateTo(Mode.SETTINGS);
            return true;
        }

        if (mode != Mode.MAIN) {
            navigateTo(Mode.MAIN);
            return true;
        }
        return false;
    }

    //  布局

    private void computeLayout() {
        if (!layoutDirty) return;

        final int phoneW = PhoneTheme.PHONE_TOTAL_WIDTH;
        final int phoneH = PhoneTheme.PHONE_TOTAL_HEIGHT;

        this.phoneLeft = (this.width - phoneW) / 2 + PhoneTheme.PHONE_BORDER;
        this.phoneTop = (this.height - phoneH) / 2 + PhoneTheme.PHONE_BORDER + PhoneTheme.SCREEN_Y_OFFSET;

        this.gridStartX = this.phoneLeft + PhoneTheme.APP_GRID_PADDING_LEFT;
        this.gridStartY = this.phoneTop + PhoneTheme.STATUS_BAR_HEIGHT + PhoneTheme.APP_GRID_PADDING_TOP;

        this.layoutDirty = false;
    }

    private void invalidateLayout() { layoutDirty = true; }

    @Override protected void init() { super.init(); invalidateLayout(); }

    @Override
    public void resize(Minecraft mc, int w, int h) { super.resize(mc, w, h); invalidateLayout(); }

    //  渲染入口

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.nowMs = System.currentTimeMillis();
        computeLayout();

        // 翻页动画走完就把起点清零。放在这里而不是藏在 slideProgress 里：
        // 那个方法在渲染和 hover 判定两处被当查询用，让它顺手改状态的话，
        // "谁先调到它"就成了行为的一部分——这类耦合出问题时极难看出来
        if (pageSlideStartMs > 0 && nowMs - pageSlideStartMs >= PhoneTheme.PAGE_SLIDE_MS) {
            pageSlideStartMs = 0;
        }

        renderBackground(g, mouseX, mouseY, partialTick);

        float scale = getAnimationScale();
        int cx = phoneLeft + PhoneTheme.PHONE_WIDTH / 2;
        int cy = phoneTop + PhoneTheme.PHONE_HEIGHT / 2;

        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().scale(scale, scale, 1.0f);
        g.pose().translate(-cx, -cy, 0);

        renderScreenBackground(g);
        renderStatusBar(g);

        switch (mode) {
            case MAIN              -> renderAppGrid(g);
            case SETTINGS          -> renderSettingsList(g, mouseX, mouseY);
            case WALLPAPER_PICKER  -> renderWallpaperPicker(g, mouseX, mouseY);
            case FONT_COLOR_PICKER -> fontColorPicker.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, font);
            case APP_MANAGER       -> appManagerPage.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, font);
            case MUSIC_PLAYER      -> renderMusicPlayer(g, mouseX, mouseY);
            case APP_STORE         -> renderAppStore(g, mouseX, mouseY);
            case APP_DETAIL        -> renderAppDetail(g, mouseX, mouseY);
            case ADDON_PAGE        -> renderAddonPage(g, mouseX, mouseY, partialTick);
            case COMPANION_APPS    -> companionApps.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, font);
            case ABOUT             -> AboutPage.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT, font);
            case GALLERY           -> renderGallery(g, mouseX, mouseY);
            case DEVICE_NAME       -> renderDeviceName(g, mouseX, mouseY, partialTick);
            case CHAT              -> renderChat(g, mouseX, mouseY);
            case CHAT_ADD_CONTACT  -> renderChatAddContact(g, mouseX, mouseY);
            case CHAT_CONVERSATION -> renderChatConversation(g, mouseX, mouseY, partialTick);
            case NOTES             -> notesList.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT, mouseX, mouseY, font);
            case CLOCK             -> ClockPage.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT, font);
            case WEATHER           -> WeatherPage.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT, font);
            case NOTE_EDIT         -> noteEditor.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, partialTick, font);
        }

        renderNavBar(g, mouseX, mouseY);

        // 外壳最后画，盖在所有内容之上 —— 这样贴图在屏幕区域里画的东西
        // （内圆角、刘海之类）才显示得出来。状态栏与导航栏都是不透明的，
        // 横跨整宽，只"画在壁纸之上"是不够的，必须在它们之后。
        // 仍在 pushPose 之内：开机动画要把外壳一起缩放
        PhoneChassis.drawFrame(g, phoneLeft, phoneTop);

        g.pose().popPose();

        if (mode == Mode.MAIN)           updateAppHover(mouseX, mouseY);
        if (mode == Mode.SETTINGS)       updateSettingsHover(mouseX, mouseY);
    }

    //  屏幕背景（壁纸）。外壳不在这儿，它画在最上层，见 render 末尾

    private void renderScreenBackground(GuiGraphics g) {
        PhoneChassis.drawScreenBackground(g, phoneLeft, phoneTop);
    }

    //  状态栏

    private void renderStatusBar(GuiGraphics g) {
        PhoneChassis.drawStatusBar(g, font, phoneLeft, phoneTop);
    }

    //  App 网格

    private void renderAppGrid(GuiGraphics g) {
        final int is = PhoneTheme.APP_ICON_SIZE;
        final int pageSize = pageSize();

        // 先结算边缘停留：这一帧可能就翻页了，翻完再算下面的预览才是对的
        updateEdgePageFlip();

        // 拖动时按"抽出来、再插进去"的结果画，而不是画原顺序再叠个提示：
        // 玩家看到的直接就是松手后的样子，不必先松手再确认自己摆对没有。
        // 用的是与 moveApp 同一个 HomeLayout.reorder，预览与落定不可能对不上
        // 只有拖动时才拷一份：reorder 会就地改，而 getApps() 交出来的是
        // Collections.unmodifiableList，改它会抛 UnsupportedOperationException。
        // 没拖的时候直接用那个只读视图，省掉每帧一次全量拷贝 —— 主屏是最常
        // 看的一页
        List<IPhoneApp> ordered = PhoneScreenRegistry.getApps();
        IPhoneApp floatingApp = null;
        int floatingIndex = -1;
        if (draggingApp && pressedAppIndex >= 0 && pressedAppIndex < ordered.size()) {
            ordered = new ArrayList<>(ordered);
            floatingApp = ordered.get(pressedAppIndex);
            floatingIndex = Math.max(0, Math.min(dragTargetIndex, ordered.size() - 1));
            HomeLayout.reorder(ordered, pressedAppIndex, floatingIndex);
        }

        // 每帧夹一次页码：卸载 App、换存档都可能让它指到不存在的页
        homePage = HomeLayout.clampPage(homePage, ordered.size(), pageSize);

        float slide = slideProgress();
        if (slide >= 1f) {
            renderPageIcons(g, ordered, homePage, 0, floatingIndex);
        } else {
            // 两页一起画，一进一出。裁到屏幕内，否则滑出去的那页会糊在机身边框上
            int dir = homePage > slideFromPage ? 1 : -1;
            int w = PhoneTheme.PHONE_WIDTH;
            int inX = Math.round((1f - slide) * dir * w);

            g.enableScissor(phoneLeft, phoneTop + PhoneTheme.STATUS_BAR_HEIGHT,
                    phoneLeft + w, dotsTop());
            renderPageIcons(g, ordered, slideFromPage, inX - dir * w, floatingIndex);
            renderPageIcons(g, ordered, homePage, inX, floatingIndex);
            g.disableScissor();
        }

        renderPageDots(g, HomeLayout.pageCount(ordered.size(), pageSize));
        renderEdgeHint(g);

        // 浮起的那张放最后画，才会盖在别的图标上面而不是被它们盖住。
        // 以鼠标为中心，手指按住哪儿它就在哪儿，不会跟手偏出去半格
        if (floatingApp != null) {
            int fx = (int) dragX - is / 2;
            int fy = (int) dragY - is / 2;
            floatingApp.renderIcon(g, fx, fy, is, 0);
            drawAppName(g, floatingApp.getDisplayName().getString(), fx, fy, is);
        }
    }

    /**
     * 拖着图标停在屏幕左右边上时，自动翻到相邻那一页。
     *
     * 没有这条路的话，App 根本挪不到别的页去：拖动只能落在【当前这一页】的格子里，
     * 而翻页要么得松手（松手就落定了）、要么得腾出另一只手滚滚轮。
     *
     * 每帧算一次而不是挂在 mouseDragged 上：玩家把图标停在边上不动时，鼠标不产生
     * 任何事件，挂在拖动事件上的计时器永远走不完。
     */
    private void updateEdgePageFlip() {
        if (!draggingApp) {
            edgeDwellSide = 0;
            edgeDwellStartMs = 0;
            return;
        }

        final int count = PhoneScreenRegistry.getAppCount();
        final int pageSize = pageSize();

        int side = 0;
        if (dragX < phoneLeft + PhoneTheme.PAGE_EDGE_WIDTH) {
            side = -1;
        } else if (dragX > phoneLeft + PhoneTheme.PHONE_WIDTH - PhoneTheme.PAGE_EDGE_WIDTH) {
            side = 1;
        }

        // 那个方向已经没有页了就当没停在边上——让提示条亮着、等半天却什么都不发生，
        // 比压根不亮更让人困惑
        if (side != 0
                && HomeLayout.clampPage(homePage + side, count, pageSize) == homePage) {
            side = 0;
        }

        if (side != edgeDwellSide) {
            edgeDwellSide = side;
            edgeDwellStartMs = nowMs;
        }
        if (side == 0) return;

        if (nowMs - edgeDwellStartMs >= PhoneTheme.PAGE_EDGE_DWELL_MS) {
            goToPage(homePage + side);
            edgeDwellStartMs = nowMs;   // 按住不放就接着往下翻

            // 页变了，同一个鼠标位置对应的落点也变了——不重算的话，预览还停在
            // 上一页的那一格上
            dragTargetIndex = dropIndexAt(dragX, dragY, count);
        }
    }

    /** 边缘提示条，随停留时长由浅到深。停满就翻页，所以它也是个进度条 */
    private void renderEdgeHint(GuiGraphics g) {
        if (edgeDwellSide == 0 || !draggingApp) return;

        float progress = Math.min(1f,
                (float) (nowMs - edgeDwellStartMs) / Math.max(1, PhoneTheme.PAGE_EDGE_DWELL_MS));

        final int w = PhoneTheme.PAGE_EDGE_WIDTH;
        int x = edgeDwellSide < 0 ? phoneLeft : phoneLeft + PhoneTheme.PHONE_WIDTH - w;
        int top = phoneTop + PhoneTheme.STATUS_BAR_HEIGHT;
        int h = dotsTop() - top;

        // 贴图靠 setColor 调制透明度整张淡入；画完必须还原，否则后面的东西
        // 会跟着一起变淡——这类"全屏莫名其妙变暗"的 bug 极难定位
        g.setColor(1f, 1f, 1f, progress);
        boolean drawn = PhoneSkin.draw(g, PhoneSkin.Element.HOME_PAGE_EDGE, x, top, w, h);
        g.setColor(1f, 1f, 1f, 1f);

        if (!drawn) {
            int alpha = (int) (progress * ((PhoneTheme.COLOR_PAGE_EDGE >>> 24) & 0xFF));
            g.fill(x, top, x + w, top + h,
                    (alpha << 24) | (PhoneTheme.COLOR_PAGE_EDGE & 0x00FFFFFF));
        }
    }

    /**
     * 画某一页的图标。
     *
     * @param ordered       已经算进拖动预览的完整顺序
     * @param page          画第几页
     * @param xOffset       整页横向偏移，翻页动画用；不在动画里时是 0
     * @param floatingIndex 正被拖着的那个的下标，-1 表示没有
     */
    private void renderPageIcons(GuiGraphics g, List<IPhoneApp> ordered,
                                 int page, int xOffset, int floatingIndex) {
        final int is = PhoneTheme.APP_ICON_SIZE;
        final int cols = PhoneTheme.APP_COLUMNS;
        final int cellW = is + PhoneTheme.APP_GRID_SPACING_X;
        final int cellH = appCellHeight();
        final int pageSize = pageSize();
        final int start = page * pageSize;

        for (int slot = 0; slot < pageSize; slot++) {
            int i = start + slot;
            if (i < 0 || i >= ordered.size()) break;

            int ix = gridStartX + (slot % cols) * cellW + xOffset;
            int iy = gridStartY + (slot / cols) * cellH;

            // 被拖的那一格只留个空槽——它本人跟着鼠标走，最后单独画
            if (i == floatingIndex) {
                PhoneSkin.drawOrFill(g, PhoneSkin.Element.HOME_DROP_SLOT,
                        ix, iy, is, is, PhoneTheme.COLOR_APP_DROP_SLOT);
                continue;
            }

            // 拖动中不画 hover 高亮：那会儿鼠标底下的格子表达的是"要插到这儿"，
            // 再高亮一次容易被理解成"松手是跟它对调"
            if (i == hoveredAppIndex && !draggingApp) {
                g.fill(ix - 2, iy - 2, ix + is + 2, iy + is + 2, PhoneTheme.COLOR_APP_PRESSED);
            }

            IPhoneApp app = ordered.get(i);
            app.renderIcon(g, ix, iy, is, 0);

            drawAppName(g, app.getDisplayName().getString(), ix, iy, is);
        }
    }

    /**
     * 翻页动画进度，1 表示已经停稳。
     *
     * 缓出（1-(1-t)²）而不是匀速：真手机的翻页是"甩出去再慢慢停住"，匀速滑动
     * 看着像幻灯片切换。
     */
    private float slideProgress() {
        if (pageSlideStartMs <= 0 || PhoneTheme.PAGE_SLIDE_MS <= 0) return 1f;

        long elapsed = nowMs - pageSlideStartMs;
        if (elapsed >= PhoneTheme.PAGE_SLIDE_MS) return 1f;

        float t = (float) elapsed / PhoneTheme.PAGE_SLIDE_MS;
        return 1f - (1f - t) * (1f - t);
    }

    /**
     * 底部那排页码点。
     *
     * 只有一页时【不画】：一个孤零零的点会让人以为还能往旁边划，划了没反应比
     * 什么都不画更让人困惑。
     */
    private void renderPageDots(GuiGraphics g, int pages) {
        if (pages <= 1) return;

        final int size = PhoneTheme.PAGE_DOT_SIZE;
        final int gap = PhoneTheme.PAGE_DOT_SPACING;

        int x = phoneLeft + (PhoneTheme.PHONE_WIDTH - (pages * size + (pages - 1) * gap)) / 2;
        int y = dotsTop() + (PhoneTheme.PAGE_DOTS_HEIGHT - size) / 2;

        for (int p = 0; p < pages; p++) {
            boolean active = p == homePage;
            PhoneSkin.drawOrFill(g,
                    active ? PhoneSkin.Element.HOME_PAGE_DOT_ACTIVE
                           : PhoneSkin.Element.HOME_PAGE_DOT,
                    x, y, size, size,
                    active ? PhoneTheme.COLOR_PAGE_DOT_ACTIVE : PhoneTheme.COLOR_PAGE_DOT);
            x += size + gap;
        }
    }

    /**
     * 点在了第几个页码点上，没点中返回 -1。
     *
     * 判定区比那 3×3 的点大一圈——按点本身的大小算的话，得对着三个像素点才能
     * 跳页，那不叫"能点"，叫"能瞄准"。
     */
    private int hitTestPageDot(double lx, double ly, int pages) {
        if (pages <= 1) return -1;

        int top = dotsTop();
        if (ly < top || ly >= top + PhoneTheme.PAGE_DOTS_HEIGHT) return -1;

        final int size = PhoneTheme.PAGE_DOT_SIZE;
        final int gap = PhoneTheme.PAGE_DOT_SPACING;
        final int step = size + gap;

        int x = phoneLeft + (PhoneTheme.PHONE_WIDTH - (pages * size + (pages - 1) * gap)) / 2;
        int idx = (int) Math.floor((lx - (x - gap / 2.0)) / step);
        return (idx >= 0 && idx < pages) ? idx : -1;
    }

    /**
     * 主屏一格的高度：图标加底下那行名字。
     *
     * 画、hover 判定、拖动落点判定三处都得用同一个值，抽出来是免得改一处漏两处——
     * 那种漏改的表现是"看着点在图标上，却没反应"，很难往格子高度上想。
     */
    private int appCellHeight() {
        return PhoneTheme.APP_ICON_SIZE
                + (int)(font.lineHeight * PhoneTheme.APP_NAME_SCALE) + 4;
    }

    /** 页码点那一条的顶边。图标区到此为止，再往下是导航栏 */
    private int dotsTop() {
        return phoneTop + PhoneTheme.PHONE_HEIGHT
                - PhoneTheme.NAV_BAR_HEIGHT - PhoneTheme.PAGE_DOTS_HEIGHT;
    }

    /** 这块屏幕一页放得下几行图标 */
    private int rowsPerPage() {
        return HomeLayout.rowsThatFit(dotsTop() - gridStartY, appCellHeight(), PhoneTheme.APP_ROWS);
    }

    /** 一页几个 App */
    private int pageSize() {
        return PhoneTheme.APP_COLUMNS * rowsPerPage();
    }

    /** 主屏一共几页 */
    private int pageCount() {
        return HomeLayout.pageCount(PhoneScreenRegistry.getAppCount(), pageSize());
    }

    /**
     * 翻到第几页。
     *
     * @return 真的换页了才 true；已经在头一页还要往前翻，返回 false
     */
    private boolean goToPage(int page) {
        int target = HomeLayout.clampPage(page, PhoneScreenRegistry.getAppCount(), pageSize());
        if (target == homePage) return false;

        slideFromPage = homePage;
        homePage = target;

        // 开场动画那 150ms 里不滑：裁剪矩形按屏幕坐标算，而那会儿整个手机
        // 正被缩放着画，两者对不上，滑出来的两页会在边缘被切歪
        pageSlideStartMs = animationDone ? System.currentTimeMillis() : 0;

        // 换页之后鼠标底下换成了另一个 App，旧的 hover 下标指的已经不是它了
        hoveredAppIndex = -1;
        return true;
    }

    /**
     * 鼠标位置对应的落点（全局下标），用于拖动时决定松手插到哪儿。
     *
     * 落点是"当前这一页的第几格"再加上页偏移——所以在第二页拖动时，松手插的是
     * 第二页的位置，而不是从头数的那一格。
     */
    private int dropIndexAt(double lx, double ly, int count) {
        if (count <= 0) return -1;

        int slot = HomeLayout.slotAt(lx, ly, gridStartX, gridStartY,
                PhoneTheme.APP_ICON_SIZE + PhoneTheme.APP_GRID_SPACING_X, appCellHeight(),
                PhoneTheme.APP_COLUMNS, rowsPerPage());

        return HomeLayout.dropIndex(homePage, slot, pageSize(), count);
    }

    /**
     * 画图标下面那行名字。
     *
     * 截断、居中、缩放三件事全在 {@link GuiUtil#drawIconLabel} 里，商店的
     * 格子共用同一份——这两处此前各抄了一遍变换代码，也各自都没做截断。
     *
     * 可用宽度是格子步距（图标宽 + 一个间距），不是图标宽：名字本来就允许
     * 比图标宽一点，只是不能宽到压着邻居。
     */
    private void drawAppName(GuiGraphics g, String name, int ix, int iy, int is) {
        GuiUtil.drawIconLabel(g, font, name, ix, iy, is,
                is + PhoneTheme.APP_GRID_SPACING_X,
                PhoneTheme.APP_NAME_SCALE, FontPalette.appName());
    }

    //  设置列表

    private void buildSettingItems() {
        if (!settingItems.isEmpty()) return;
        settingItems.add(new SettingItem(
                Component.translatable("mcphone.gui.wallpaper").getString(),
                () -> navigateTo(Mode.WALLPAPER_PICKER)));
        settingItems.add(new SettingItem(
                Component.translatable("mcphone.settings.font_color").getString(),
                () -> navigateTo(Mode.FONT_COLOR_PICKER),
                PhoneScreen::currentFontColorLabel));
        settingItems.add(new SettingItem(
                Component.translatable("mcphone.settings.device_name").getString(),
                () -> navigateTo(Mode.DEVICE_NAME),
                this::currentDeviceNameLabel));
        settingItems.add(new SettingItem(
                Component.translatable("mcphone.app.app_manager").getString(),
                () -> navigateTo(Mode.APP_MANAGER)));
        settingItems.add(new SettingItem(
                Component.translatable("mcphone.gui.about").getString(),
                () -> navigateTo(Mode.ABOUT)));
    }

    private void renderSettingsList(GuiGraphics g, int mouseX, int mouseY) {
        buildSettingItems();

        int y = phoneTop + PhoneTheme.STATUS_BAR_HEIGHT + 4;
        int x = phoneLeft + 6;
        int w = PhoneTheme.PHONE_WIDTH - 12;
        int bottom = phoneTop + PhoneTheme.PHONE_HEIGHT - PhoneTheme.NAV_BAR_HEIGHT;

        // 标题
        String title = Component.translatable("mcphone.gui.settings").getString();
        g.drawString(font, title, x, y, FontPalette.title(), true);
        y += font.lineHeight + 4;

        // 分割线
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 4;

        for (int i = 0; i < settingItems.size(); i++) {
            if (y + font.lineHeight + 6 > bottom) break;

            SettingItem item = settingItems.get(i);
            int rowH = font.lineHeight + 4;

            if (i == hoveredSettingIdx) {
                g.fill(x, y, x + w, y + rowH, PhoneTheme.COLOR_ROW_HOVER);
            }

            g.drawString(font, item.label(), x + 2, y + 2, FontPalette.body(), false);

            // 有当前值就显示值，否则画右箭头
            String right = item.value() != null ? item.value().get() : ">";
            // 值过长会与左侧标题撞上，按剩余宽度截断
            int maxRightW = w - font.width(item.label()) - 10;
            if (font.width(right) > maxRightW) {
                right = font.plainSubstrByWidth(right, Math.max(6, maxRightW - 4)) + "…";
            }
            int ax = x + w - font.width(right) - 4;
            g.drawString(font, right, ax, y + 2, FontPalette.subtle(), false);

            y += rowH + 2;
        }

        // 空列表提示
        if (settingItems.isEmpty()) {
            String noItems = Component.translatable("mcphone.gui.no_settings").getString();
            g.drawString(font, noItems, x, y, FontPalette.subtle(), false);
        }
    }

    //  App 管理器




    //  壁纸选择器

    private void renderWallpaperPicker(GuiGraphics g, int mx, int my) {
        wallpaperPicker.render(g, phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                mx, my, font);
    }

    //  音乐播放器

    private void renderMusicPlayer(GuiGraphics g, int mx, int my) {
        musicPage.render(g, phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                mx, my, font);
    }

    //  应用商店

    private void renderAppStore(GuiGraphics g, int mx, int my) {
        appStore.render(g, phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                mx, my, font);
    }

    private void renderAppDetail(GuiGraphics g, int mx, int my) {
        appDetail.render(g, phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                mx, my, font);
    }

    //  设备名称

    private void renderDeviceName(GuiGraphics g, int mx, int my, float partialTick) {
        deviceNameEditor.render(g, phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                mx, my, partialTick, font);
    }

    //  聊天

    private void renderChat(GuiGraphics g, int mx, int my) {
        chatList.render(g, phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                mx, my, font);
    }

    private void renderChatAddContact(GuiGraphics g, int mx, int my) {
        chatAddContact.render(g, phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                mx, my, font);
    }

    private void renderChatConversation(GuiGraphics g, int mx, int my, float partialTick) {
        chatConversation.render(g, phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                mx, my, partialTick, font);
    }

    /** 设置列表右侧显示的当前设备名，未命名时显示占位文案 */
    private String currentDeviceNameLabel() {
        if (minecraft == null || minecraft.player == null) return "";
        String name = location.resolve(minecraft.player)
                .get(com.november.mcphone.core.ModDataComponents.DEVICE_NAME.get());
        return (name == null || name.isBlank())
                ? Component.translatable("mcphone.settings.device_name_unset").getString()
                : name;
    }

    /** 设置列表右侧显示的当前字体颜色 */
    private static String currentFontColorLabel() {
        return Component.translatable(FontPalette.current().translationKey()).getString();
    }

    //  相册

    private void renderGallery(GuiGraphics g, int mx, int my) {
        gallery.render(g, phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                mx, my, font);
    }

    //  底部导航栏

    private void renderNavBar(GuiGraphics g, int mouseX, int mouseY) {
        PhoneChassis.drawNavBar(g, font, phoneLeft, phoneTop, mouseX, mouseY);
    }

    //  动画

    private float getAnimationScale() {
        if (animationDone) return 1f;
        long elapsed = nowMs - openTimeMs;
        int dur = PhoneTheme.OPEN_ANIMATION_MS;
        if (elapsed >= dur) { animationDone = true; return 1f; }
        float t = (float) elapsed / dur;
        float c1 = 1.70158f, c3 = c1 + 1;
        return (float)(1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2)) * 0.4f + 0.6f;
    }

    //  坐标换算

    /**
     * 撤掉开场动画的缩放，回到界面元素真正被摆在哪儿的那套坐标。
     *
     * render() 在开场动画期间以手机中心为原点缩放了整个画面，因此命中判定
     * 必须做同样的逆变换，否则那 150 毫秒里点击位置会偏。
     *
     * 【结果仍然是屏幕坐标】，原点没有挪到手机左上角——所以它算出来的值可以
     * 直接和 phoneLeft、gridStartY、dotsTop() 这些比。
     *
     * 1.3.24 之前这两个方法叫 toLocalX/toLocalY，存下来的字段叫
     * pressLocalX/dragLocalX。那个 "Local" 让人以为是相对手机的局部坐标，
     * 于是读到 "dragLocalX &lt; phoneLeft + ..." 会觉得是个 bug——局部坐标
     * 跟屏幕坐标比当然不对，但它其实是对的，是名字在骗人。
     */
    private double unscaledX(double mx) {
        int cx = phoneLeft + PhoneTheme.PHONE_WIDTH / 2;
        return (mx - cx) / getAnimationScale() + cx;
    }

    private double unscaledY(double my) {
        int cy = phoneTop + PhoneTheme.PHONE_HEIGHT / 2;
        return (my - cy) / getAnimationScale() + cy;
    }

    /**
     * 点击是否落在手机机身（含边框）内。
     * 矩形与 renderPhoneFrame() 绘制边框所用坐标一致，保证判定与视觉对齐。
     */
    private boolean isInsidePhone(double mx, double my) {
        double lx = unscaledX(mx);
        double ly = unscaledY(my);
        int fl = phoneLeft - PhoneTheme.PHONE_BORDER;
        int ft = phoneTop - PhoneTheme.PHONE_BORDER;
        return lx >= fl && lx < fl + PhoneTheme.PHONE_TOTAL_WIDTH
            && ly >= ft && ly < ft + PhoneTheme.PHONE_TOTAL_HEIGHT;
    }

    //  鼠标 hover

    private void updateAppHover(int mx, int my) {
        // 走 unscaledX/Y 而不是把那两行算式再抄一遍：抄的那份此前已经和
        // 正主分开住了，改动画曲线时只会有一个人被想起来，而结果是
        // "开机那一瞬间点图标点不准"——短到没人抓得住
        int lx = (int) unscaledX(mx);
        int ly = (int) unscaledY(my);

        final int count = PhoneScreenRegistry.getAppCount();
        final int is = PhoneTheme.APP_ICON_SIZE;
        final int cols = PhoneTheme.APP_COLUMNS;
        final int cellW = is + PhoneTheme.APP_GRID_SPACING_X;
        final int cellH = appCellHeight();
        final int pageSize = pageSize();
        final int start = homePage * pageSize;

        // 只看当前这一页：别的页的图标压根没画出来，"停在"它们上面没有意义
        hoveredAppIndex = -1;

        // 正在翻页动画里就不认 hover：图标那会儿还在半路上，按它算命中会点开
        // 一个不在鼠标底下的 App
        if (slideProgress() < 1f) return;
        for (int slot = 0; slot < pageSize; slot++) {
            int i = start + slot;
            if (i >= count) break;

            int ix = gridStartX + (slot % cols) * cellW;
            int iy = gridStartY + (slot / cols) * cellH;
            if (lx >= ix && lx <= ix + is && ly >= iy && ly <= iy + is + 6) {
                hoveredAppIndex = i;   // 全局下标，不是 slot
                return;
            }
        }
    }

    private void updateSettingsHover(int mx, int my) {
        int y = phoneTop + PhoneTheme.STATUS_BAR_HEIGHT + 4 + font.lineHeight + 4 + 4;
        int x = phoneLeft + 6;
        int w = PhoneTheme.PHONE_WIDTH - 12;

        hoveredSettingIdx = -1;
        for (int i = 0; i < settingItems.size(); i++) {
            int rowH = font.lineHeight + 4;
            if (mx >= x && mx <= x + w && my >= y && my <= y + rowH) {
                hoveredSettingIdx = i;
                return;
            }
            y += rowH + 2;
        }
    }

    //  鼠标点击

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);

        // 导航栏抢在各模式之前：它在每个界面都存在，交给各 case 自己处理
        // 就得处处重复。各界面的内容都画在导航栏上方，不会争抢同一块区域
        switch (PhoneChassis.hitTestNavBar(mx, my, phoneLeft, phoneTop)) {
            case BACK -> {
                // 主屏上按返回不关机——真实手机就是这样。关机走 ESC 或点机身外
                goBackOneLevel();
                return true;
            }
            case HOME -> {
                navigateTo(Mode.MAIN);
                return true;
            }
            case TASKS -> {
                // 多任务尚未实现，先吃掉点击，免得穿透到下面的界面
                return true;
            }
            case NONE -> { }
        }

        return switch (mode) {
            case MAIN -> {
                // 页码点抢在图标之前：它在图标区【下方】，两者不重叠，
                // 但先判它一次就不必担心将来图标区长高了压过来
                int dot = hitTestPageDot(unscaledX(mx), unscaledY(my), pageCount());
                if (dot >= 0) {
                    homePage = dot;
                    hoveredAppIndex = -1;
                    yield true;
                }
                if (hoveredAppIndex >= 0) {
                    // 先记着是哪一格，别急着开——这一下可能是要把它拖走。
                    // 到底算点开还是算挪位置，由 mouseReleased 定
                    pressedAppIndex = hoveredAppIndex;
                    pressX = unscaledX(mx);
                    pressY = unscaledY(my);
                    dragTargetIndex = pressedAppIndex;
                    draggingApp = false;
                    yield true;
                }
                // 机身内的空白：记下来，横着拖它就是翻页
                if (isInsidePhone(mx, my)) {
                    pressedBlank = true;
                    pressX = unscaledX(mx);
                    pressY = unscaledY(my);
                    yield true;
                }
                // 只有点在手机机身外才关闭
                onClose();
                yield true;
            }
            case SETTINGS -> {
                if (hoveredSettingIdx >= 0 && hoveredSettingIdx < settingItems.size()) {
                    settingItems.get(hoveredSettingIdx).action().run();
                    yield true;
                }
                yield true;
            }
            case WALLPAPER_PICKER -> {
                if (wallpaperPicker.mouseClicked(button)) {
                    navigateTo(Mode.SETTINGS);
                }
                yield true;
            }
            case FONT_COLOR_PICKER -> {
                if (fontColorPicker.mouseClicked(button)) {
                    navigateTo(Mode.SETTINGS);
                }
                yield true;
            }
            case APP_MANAGER -> {
                appManagerPage.mouseClicked(mx, my, button);
                yield true;
            }
            case MUSIC_PLAYER -> {
                musicPage.mouseClicked(mx, my, button);
                yield true;
            }
            case APP_STORE -> {
                appStore.mouseClicked(mx, my, button);
                // 商店只提出请求，由这里决定去哪个界面——
                // 组件不该知道 PhoneScreen 的导航结构
                AppInfo open = appStore.consumeOpenRequest();
                if (open != null) {
                    appDetail.open(open);
                    navigateTo(Mode.APP_DETAIL);
                }
                if (appStore.consumeCompanionRequest()) navigateTo(Mode.COMPANION_APPS);
                yield true;
            }
            case COMPANION_APPS -> {
                companionApps.mouseClicked(mx, my, button);
                yield true;
            }
            case ADDON_PAGE -> {
                // 返回 false 时不再往下落：下面的默认分支里"点手机外面＝关机"，
                // 而附属页面里的一次空点击不该把手机关掉
                callPage(p -> p.mouseClicked(mx, my, button));
                yield true;
            }
            case ABOUT, CLOCK, WEATHER -> {
                // 这三页只有信息，没有可点的东西。仍然 yield true 把点击吃掉，
                // 否则会落到下面的默认分支去，行为不好预料
                yield true;
            }
            case APP_DETAIL -> {
                appDetail.mouseClicked(mx, my, button);
                // 装成功了要让商店把它从可下载列表里去掉，再退回列表。
                // 顺序不能反：先 navigateTo 的话，那次 reset 会把刷新
                // 请求一起清掉
                if (appDetail.consumeInstalledRequest()) appStore.onInstalled();
                if (appDetail.consumeBackRequest()) navigateTo(Mode.APP_STORE);
                yield true;
            }
            case GALLERY -> {
                gallery.mouseClicked(mx, my, button);
                yield true;
            }
            case DEVICE_NAME -> {
                // 点了保存或取消都回到设置列表
                if (deviceNameEditor.mouseClicked(mx, my, button)) navigateTo(Mode.SETTINGS);
                yield true;
            }
            case CHAT -> {
                chatList.mouseClicked(mx, my, button);
                // 点了传送就关机：人已经在几百格外，还举着手机的话看不见
                // 自己落在哪。包由列表自己发了，这里只管把界面收掉
                if (chatList.consumeCloseRequest()) {
                    onClose();
                    yield true;
                }
                // 会话列表只提出请求，由这里决定去哪个界面——
                // 组件不该知道 PhoneScreen 的导航结构
                UUID open = chatList.consumeOpenRequest();
                if (open != null) {
                    pendingConversationPeer = open;
                    navigateTo(Mode.CHAT_CONVERSATION);
                }
                if (chatList.consumeAddContactRequest()) {
                    navigateTo(Mode.CHAT_ADD_CONTACT);
                }
                yield true;
            }
            case CHAT_ADD_CONTACT -> {
                chatAddContact.mouseClicked(mx, my, button);
                yield true;
            }
            case CHAT_CONVERSATION -> {
                chatConversation.mouseClicked(mx, my, button);
                yield true;
            }
            case NOTES -> {
                notesList.mouseClicked(mx, my, button);
                // 列表只提出请求，由这里决定去哪个界面——
                // 组件不该知道 PhoneScreen 的导航结构
                Integer open = notesList.consumeOpenRequest();
                if (open != null) {
                    noteEditor.open(open);
                    navigateTo(Mode.NOTE_EDIT);
                } else if (notesList.consumeNewRequest()) {
                    noteEditor.openNew();
                    navigateTo(Mode.NOTE_EDIT);
                }
                yield true;
            }
            case NOTE_EDIT -> {
                noteEditor.mouseClicked(mx, my, button);
                // 存了或删了都退回列表，列表会随服务端回发的新数据刷新
                if (noteEditor.consumeBackRequest()) navigateTo(Mode.NOTES);
                yield true;
            }
        };
    }

    //  拖动 / 松手 / 滚轮

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        // 主屏拖动排序。没过阈值之前什么都不做，好让这一下还有机会被当成点击
        if (mode == Mode.MAIN && button == 0 && pressedAppIndex >= 0) {
            double lx = unscaledX(mx);
            double ly = unscaledY(my);

            if (!draggingApp) {
                if (Math.abs(lx - pressX) < PhoneTheme.APP_DRAG_THRESHOLD
                        && Math.abs(ly - pressY) < PhoneTheme.APP_DRAG_THRESHOLD) {
                    return true;
                }
                draggingApp = true;
            }

            dragX = lx;
            dragY = ly;
            dragTargetIndex = dropIndexAt(lx, ly, PhoneScreenRegistry.getAppCount());
            return true;
        }

        // 空白处横着拖 = 翻页。真手机的划屏，鼠标上的等价物
        if (mode == Mode.MAIN && button == 0 && pressedBlank) {
            double lx = unscaledX(mx);
            double moved = lx - pressX;
            if (Math.abs(moved) >= PhoneTheme.PAGE_SWIPE_THRESHOLD) {
                // 往左划＝内容跟着往左走＝看后面那一页
                goToPage(homePage + (moved < 0 ? 1 : -1));
                // 不管翻没翻成都重设起点：翻成了才能接着往下划连翻两页，
                // 没翻成（到头了）也得重设，否则按住不动会每帧重复触发
                pressX = lx;
            }
            return true;
        }

        // 多行输入框靠拖动选中文本，不转发的话选不了
        if (mode == Mode.NOTE_EDIT && noteEditor.mouseDragged(mx, my, button, dx, dy)) return true;
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    /**
     * 松手 —— 主屏上这一下才定性：刚才那次按下算"点开"还是"挪位置"。
     *
     * 开 App 放在这里而不是 mouseClicked，就是为了留出这个判断的余地。代价是
     * 点击的响应晚了一个"松手"，收益是图标能拖；真手机也是松手才启动 App。
     */
    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (mode == Mode.MAIN && button == 0 && pressedBlank) {
            pressedBlank = false;
            return true;
        }
        if (mode == Mode.MAIN && button == 0 && pressedAppIndex >= 0) {
            int from = pressedAppIndex;
            int to = dragTargetIndex;
            boolean dragged = draggingApp;

            // 先把状态清干净再动作：launchApp 可能当场跳去别的界面，
            // 之后再改这几个字段就是在给一个已经不在的界面收尾
            pressedAppIndex = -1;
            dragTargetIndex = -1;
            draggingApp = false;

            if (dragged) {
                PhoneScreenRegistry.moveApp(from, to);
                // 顺序变了，原来那个 hover 下标指的已经不是同一个 App，
                // 留着会让高亮框停在错的格子上直到鼠标下次移动
                hoveredAppIndex = -1;
            } else {
                IPhoneApp app = PhoneScreenRegistry.getApp(from);
                if (app != null) launchApp(app);
            }
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        // 主屏滚轮翻页。真手机是横划，鼠标上最接近的等价物就是滚轮——
        // 往下滚＝往后翻，与所有列表一致
        if (mode == Mode.MAIN && scrollY != 0) {
            if (goToPage(homePage + (scrollY > 0 ? -1 : 1))) return true;
            // 只有一页、或已经到头：仍然把滚轮吃掉，别让它穿到下面去
            return true;
        }
        if (mode == Mode.GALLERY && gallery.mouseScrolled(scrollY)) return true;
        if (mode == Mode.CHAT && chatList.mouseScrolled(scrollY)) return true;
        if (mode == Mode.CHAT_ADD_CONTACT && chatAddContact.mouseScrolled(scrollY)) return true;
        if (mode == Mode.CHAT_CONVERSATION && chatConversation.mouseScrolled(scrollY)) return true;
        if (mode == Mode.NOTES && notesList.mouseScrolled(scrollY)) return true;
        if (mode == Mode.MUSIC_PLAYER && musicPage.mouseScrolled(scrollY, my)) return true;
        if (mode == Mode.NOTE_EDIT && noteEditor.mouseScrolled(mx, my, scrollX, scrollY)) return true;
        if (mode == Mode.ADDON_PAGE
                && callPage(p -> p.mouseScrolled(mx, my, scrollY))) return true;
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    //  键盘

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            // 不管开到第几层，ESC 一下就关机——它在原版里的意思始终是
            // "把这个界面收掉"，玩家不会指望按它是往回走一格。
            // 退一层交给导航栏的 ◁，两者分工，不再共用 goBackOneLevel
            onClose();
            return true;
        }
        // 带输入框的界面必须抢在背包键判定之前，且无论输入框是否消费都要
        // 吃掉按键：否则打个 "e" 就会命中背包键，手机当场关掉。打拼音时
        // 一定会按到 e，中文用户尤其躲不开。
        // ESC 已在上面单独处理，不会被这里吞掉
        if (mode == Mode.DEVICE_NAME) {
            deviceNameEditor.keyPressed(keyCode, scanCode, modifiers);
            if (deviceNameEditor.consumeBackRequest()) navigateTo(Mode.SETTINGS);
            return true;
        }
        if (mode == Mode.CHAT_CONVERSATION) {
            chatConversation.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if (mode == Mode.NOTE_EDIT) {
            noteEditor.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        // 附属页面自称有输入框时，同样要抢在背包键之前——理由与上面三处一样：
        // 打拼音一定会按到 e，落到背包键判定上手机当场关掉、内容全丢
        if (mode == Mode.ADDON_PAGE && pageCapturesKeyboard()) {
            callPage(p -> p.keyPressed(keyCode, scanCode, modifiers));
            return true;
        }

        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            if (mode != Mode.MAIN) back();
            else onClose();
            return true;
        }
        // 相册的方向键翻页放在最后：ESC 与背包键优先，
        // 免得有人把背包键绑成方向键时被相册吃掉
        if (mode == Mode.GALLERY && gallery.keyPressed(keyCode)) return true;
        if (mode == Mode.ADDON_PAGE
                && callPage(p -> p.keyPressed(keyCode, scanCode, modifiers))) return true;

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 字符输入。EditBox 靠这个收字符，包括输入法提交的汉字与 Ctrl+V 粘贴。
     *
     * 这是所有文字进入界面的唯一通道，原版按 T 的聊天框走的也是它，
     * 详见 DeviceNameEditor 类注释。
     */
    @Override
    public boolean charTyped(char c, int modifiers) {
        if (mode == Mode.ADDON_PAGE
                && callPage(p -> p.charTyped(c, modifiers))) return true;
        if (mode == Mode.DEVICE_NAME && deviceNameEditor.charTyped(c, modifiers)) return true;
        if (mode == Mode.CHAT_CONVERSATION && chatConversation.charTyped(c, modifiers)) return true;
        if (mode == Mode.NOTE_EDIT && noteEditor.charTyped(c, modifiers)) return true;
        return super.charTyped(c, modifiers);
    }

    @Override public void onClose() { super.onClose(); }

    /**
     * 界面被移除时释放相册贴图。
     *
     * 用 removed() 而不是 onClose()：被别的界面顶掉时（如按 E 开背包）
     * onClose 不会触发，贴图就一直占着显存了。
     */
    @Override
    public void removed() {
        if (mode == Mode.GALLERY) gallery.close();
        // 同理：手机被顶掉时会话缓存也该放掉，否则新消息还会往里追加
        if (mode == Mode.CHAT_CONVERSATION) chatConversation.close();
        if (mode == Mode.NOTE_EDIT) noteEditor.close();

        // 附属页面同理，而且更要紧：IPhonePage.onClose() 的文档里写着"一定会被
        // 调用——返回键、ESC、关手机、断线都会走到"。那句承诺就靠这一行兑现。
        //
        // 只挂在 navigateTo 上是不够的：关手机、被别的界面顶掉、退出世界这几条
        // 路径根本不经过 navigateTo，附属那边的资源就永远不会被释放。这与 1.1.28
        // 修掉的"浏览器只在 onClose 释放"是同一类坑
        closeAddonPage();

        super.removed();
    }
    @Override public boolean isPauseScreen() { return false; }
}
