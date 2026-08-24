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
import com.november.mcphone.feature.settings.client.SettingsList;
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

    // ---- 设置列表 ----
    /** 内容由本类构建（每一项的动作都是跳到某一页），画与判命中交给组件 */
    private final SettingsList settingsList = new SettingsList();
    private final List<SettingsList.Item> settingItems = new ArrayList<>();

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





    // ---- 主屏拖动排序 ----
    //
    // 按下【不】立即开 App：这一下可能是要把图标拖去别的格子。按下只记下是哪一格，
    // 真正开 App 推迟到 mouseReleased，位移没超过阈值才算一次点击。
    // 这是"能拖"必然要付的代价——按下的那一刻还看不出玩家想干什么。








    // ---- 布局缓存 ----
    /** 主屏那一页。图标网格、拖动排序、分页翻页都归它 */
    private final HomeGrid homeGrid = new HomeGrid();

    private int phoneLeft, phoneTop;
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
        settingsList.open();
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
            case MAIN              -> homeGrid.render(g, phoneLeft, phoneTop, font,
                    nowMs, animationDone, unscaledX(mouseX), unscaledY(mouseY));
            case SETTINGS          -> {
                buildSettingItems();
                settingsList.render(g, phoneLeft, phoneTop,
                        PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                        PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                        mouseX, mouseY, font);
            }
            case WALLPAPER_PICKER  -> wallpaperPicker.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, font);
            case FONT_COLOR_PICKER -> fontColorPicker.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, font);
            case APP_MANAGER       -> appManagerPage.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, font);
            case MUSIC_PLAYER      -> musicPage.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, font);
            case APP_STORE         -> appStore.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, font);
            case APP_DETAIL        -> appDetail.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, font);
            case ADDON_PAGE        -> renderAddonPage(g, mouseX, mouseY, partialTick);
            case COMPANION_APPS    -> companionApps.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, font);
            case ABOUT             -> AboutPage.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT, font);
            case GALLERY           -> gallery.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, font);
            case DEVICE_NAME       -> deviceNameEditor.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, partialTick, font);
            case CHAT              -> chatList.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, font);
            case CHAT_ADD_CONTACT  -> chatAddContact.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, font);
            case CHAT_CONVERSATION -> chatConversation.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, partialTick, font);
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
















    //  设置列表

    /**
     * 建一次就够，之后交给 {@link SettingsList} 拿着画。
     *
     * 标签在建的这一刻定死。PhoneScreen 每次开机都是新造的，所以换语言
     * 之后重开一次手机就跟上了，不必每帧重建。
     */
    private void buildSettingItems() {
        if (!settingItems.isEmpty()) return;

        settingItems.add(new SettingsList.Item(
                Component.translatable("mcphone.gui.wallpaper").getString(),
                () -> navigateTo(Mode.WALLPAPER_PICKER)));
        settingItems.add(new SettingsList.Item(
                Component.translatable("mcphone.settings.font_color").getString(),
                () -> navigateTo(Mode.FONT_COLOR_PICKER),
                PhoneScreen::currentFontColorLabel));
        settingItems.add(new SettingsList.Item(
                Component.translatable("mcphone.settings.device_name").getString(),
                () -> navigateTo(Mode.DEVICE_NAME),
                this::currentDeviceNameLabel));
        settingItems.add(new SettingsList.Item(
                Component.translatable("mcphone.app.app_manager").getString(),
                () -> navigateTo(Mode.APP_MANAGER)));
        settingItems.add(new SettingsList.Item(
                Component.translatable("mcphone.gui.about").getString(),
                () -> navigateTo(Mode.ABOUT)));

        settingsList.setItems(settingItems);
    }


    //  App 管理器




    //  壁纸选择器


    //  音乐播放器


    //  应用商店



    //  设备名称


    //  聊天




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
     * 直接和 phoneLeft、phoneTop 这些比，也可以原样交给按屏幕坐标排版的
     * 各个页面组件（主屏就是这么收的）。
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
                double lx = unscaledX(mx);
                double ly = unscaledY(my);

                if (homeGrid.mousePressed(lx, ly)) yield true;

                // 主屏没接住 —— 那要么按在机身内的空白上（横着拖它就是翻页），
                // 要么按在机身外。"机身在哪儿""按外面要不要关机"是本类的事，
                // 所以这两句留在这儿
                if (isInsidePhone(mx, my)) {
                    homeGrid.pressBlank(lx, ly);
                    yield true;
                }
                onClose();
                yield true;
            }
            case SETTINGS -> {
                settingsList.mouseClicked(mx, my, button);
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
        if (mode == Mode.MAIN && button == 0
                && homeGrid.mouseDragged(unscaledX(mx), unscaledY(my))) {
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
        if (mode == Mode.MAIN && button == 0
                && homeGrid.mouseReleased(unscaledX(mx), unscaledY(my))) {
            // 松手定性为"点开"时主屏只把 App 记下来，去哪个界面由这里决定 ——
            // 组件不该知道 PhoneScreen 的导航结构
            IPhoneApp launch = homeGrid.consumeLaunchRequest();
            if (launch != null) launchApp(launch);
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        // 主屏滚轮翻页。真手机是横划，鼠标上最接近的等价物就是滚轮——
        // 往下滚＝往后翻，与所有列表一致
        if (mode == Mode.MAIN && homeGrid.mouseScrolled(scrollY)) return true;
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
