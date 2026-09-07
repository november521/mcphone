package com.november.mcphone.core.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.store.AppInfo;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.core.PhoneLocation;
import com.november.mcphone.feature.chat.client.ChatAddContact;
import com.november.mcphone.feature.chat.client.ChatConversation;
import com.november.mcphone.core.ServerConfig;
import com.november.mcphone.feature.chat.client.ChatImageCache;
import com.november.mcphone.feature.chat.client.ChatImageSender;
import com.november.mcphone.feature.chat.client.ChatList;
import com.november.mcphone.feature.chat.client.ChatMediaPicker;
import com.november.mcphone.feature.chat.client.StickerLibrary;
import com.november.mcphone.feature.gallery.client.Gallery;
import com.november.mcphone.feature.gallery.client.PhotoLibrary;
import com.november.mcphone.feature.music.client.MusicPage;
import com.november.mcphone.feature.notes.client.NoteEditor;
import com.november.mcphone.feature.notes.client.NotesList;
import com.november.mcphone.feature.reader.BookRef;
import com.november.mcphone.feature.reader.client.BookList;
import com.november.mcphone.feature.reader.client.source.BookSources;
import com.november.mcphone.feature.settings.client.AboutPage;
import com.november.mcphone.feature.settings.client.AppManagerDetail;
import com.november.mcphone.feature.settings.client.AppManagerPage;
import com.november.mcphone.feature.settings.client.SettingsList;
import com.november.mcphone.feature.settings.client.DeviceNameEditor;
import com.november.mcphone.feature.settings.client.FontColorPicker;
import com.november.mcphone.feature.settings.client.UiScalePage;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 手机主屏幕 GUI：管理各页面之间的导航（{@link Mode}）、分发输入、兜住附属页面的异常 */
public final class PhoneScreen extends Screen {

    public enum Mode { MAIN, SETTINGS, WALLPAPER_PICKER, FONT_COLOR_PICKER, UI_SCALE, APP_MANAGER, APP_MANAGER_DETAIL, MUSIC_PLAYER, APP_STORE, APP_DETAIL, COMPANION_APPS, ADDON_PAGE, ABOUT, GALLERY, DEVICE_NAME, CHAT, CHAT_ADD_CONTACT, CHAT_CONVERSATION, CHAT_PHOTO_PICKER, CHAT_STICKER_PICKER, NOTES, NOTE_EDIT, CLOCK, WEATHER, READER }

    private final long openTimeMs;
    private boolean animationDone;

    private Mode mode = Mode.MAIN;
    private final WallpaperPicker wallpaperPicker = new WallpaperPicker();
    private final FontColorPicker fontColorPicker = new FontColorPicker();
    private final UiScalePage uiScalePage = new UiScalePage();

    private final SettingsList settingsList = new SettingsList();
    private final List<SettingsList.Item> settingItems = new ArrayList<>();

    private final AppManagerPage appManagerPage = new AppManagerPage();

    private final AppManagerDetail appManagerDetail = new AppManagerDetail();

    /** 1.9.1 起有滚动状态，不再是纯静态的一页 */
    private final AboutPage aboutPage = new AboutPage();

    /** 在 App 管理器里点中、正要进详情页的那一个 */
    private IPhoneApp pendingManagedApp;

    private final MusicPage musicPage = new MusicPage();

    private final AppStore appStore = new AppStore();
    private final AppDetail appDetail = new AppDetail();
    private final CompanionApps companionApps = new CompanionApps();

    /**
     * 附属 App 当前打开的那一页，null 表示没有。
     * 每个回调都要兜 Throwable 而不是 Exception：引用没装的模组类抛的是 NoClassDefFoundError。
     */
    private IPhonePage addonPage = null;

    private final Gallery gallery = new Gallery();

    private final DeviceNameEditor deviceNameEditor = new DeviceNameEditor();

    private final ChatList chatList = new ChatList();
    private final ChatAddContact chatAddContact = new ChatAddContact();
    private final ChatConversation chatConversation = new ChatConversation();
    /**
     * 「挑一张发出去」的两页：一页盯着截图目录，一页盯着表情目录。
     * 同一个类的两个实例——除了目录与标题，它们做的是一模一样的事，见 {@link ChatMediaPicker}。
     */
    private final ChatMediaPicker chatPhotoPicker = new ChatMediaPicker(
            PhotoLibrary.folder(), "mcphone.chat.pick_photo", "mcphone.chat.pick_photo_empty", false);

    private final ChatMediaPicker chatStickerPicker = new ChatMediaPicker(
            StickerLibrary.folder(), "mcphone.chat.pick_sticker", "mcphone.chat.pick_sticker_empty", true);

    private final NotesList notesList = new NotesList();
    private final NoteEditor noteEditor = new NoteEditor();

    private final BookList bookList = new BookList();

    /** 待打开的会话对端：navigateTo 不带参数，进会话前先存这里 */
    private UUID pendingConversationPeer;

    /** 手机在玩家身上的位置，设备名要写回这一部 */
    private final PhoneLocation location;

    private final HomeGrid homeGrid = new HomeGrid();

    private int phoneLeft, phoneTop;
    private boolean layoutDirty = true;
    private long nowMs;

    /** 续开只做一次：init 还会因为改窗口大小再来一遍，那时不该把玩家挪回去 */
    private boolean sessionResumed;

    public PhoneScreen(PhoneLocation location) {
        super(Component.translatable("mcphone.gui.home"));
        this.location = location;
        this.openTimeMs = System.currentTimeMillis();
        this.animationDone = PhoneTheme.OPEN_ANIMATION_MS <= 0;
    }

    public void navigateTo(Mode target) {
        if (this.mode == target) return;

        // 进详情页不算离开商店：reset 会清掉页码
        if (this.mode == Mode.APP_STORE
                && target != Mode.APP_STORE && target != Mode.APP_DETAIL
                && target != Mode.COMPANION_APPS) {
            appStore.reset();
        }

        // ESC 关机、被顶掉、断线不经过 navigateTo，那几条由 removed() 兜住
        if (this.mode == Mode.ADDON_PAGE && target != Mode.ADDON_PAGE) closeAddonPage();

        if (this.mode == Mode.COMPANION_APPS) companionApps.reset();
        if (target == Mode.COMPANION_APPS) companionApps.refresh();

        if (this.mode == Mode.GALLERY) gallery.close();
        if (target == Mode.GALLERY) gallery.open();

        if (this.mode == Mode.DEVICE_NAME) deviceNameEditor.close();
        if (target == Mode.DEVICE_NAME) deviceNameEditor.open(location);

        if (this.mode == Mode.CHAT) chatList.close();
        if (target == Mode.CHAT) chatList.open();

        if (this.mode == Mode.CHAT_ADD_CONTACT) chatAddContact.close();
        if (target == Mode.CHAT_ADD_CONTACT) chatAddContact.open();

        if (this.mode == Mode.CHAT_CONVERSATION) chatConversation.close();
        if (target == Mode.CHAT_CONVERSATION) chatConversation.open(pendingConversationPeer);

        if (this.mode == Mode.CHAT_PHOTO_PICKER) chatPhotoPicker.close();
        if (target == Mode.CHAT_PHOTO_PICKER) chatPhotoPicker.open();

        if (this.mode == Mode.CHAT_STICKER_PICKER) chatStickerPicker.close();
        if (target == Mode.CHAT_STICKER_PICKER) chatStickerPicker.open();

        if (target == Mode.WALLPAPER_PICKER) {
            WallpaperStore.refresh();
            wallpaperPicker.open();
        }

        if (this.mode == Mode.NOTES) notesList.close();
        if (target == Mode.NOTES) notesList.open();

        // 每次进书架都重扫一遍书源，理由见 BookList.open()
        if (this.mode == Mode.READER) bookList.close();
        if (target == Mode.READER) bookList.open();
        if (target == Mode.APP_MANAGER) appManagerPage.open();

        if (this.mode == Mode.APP_MANAGER_DETAIL) appManagerDetail.close();
        if (target == Mode.APP_MANAGER_DETAIL) appManagerDetail.open(pendingManagedApp);

        // 离开音乐页不停音乐，close 只收界面
        if (this.mode == Mode.MUSIC_PLAYER) musicPage.close();
        if (target == Mode.MUSIC_PLAYER) musicPage.open();

        if (this.mode == Mode.NOTE_EDIT) noteEditor.close();

        if (this.mode == Mode.FONT_COLOR_PICKER) fontColorPicker.close();
        // 拖着条离开这一页的话，拖动状态要收掉，否则下次进来还当自己在拖
        if (this.mode == Mode.UI_SCALE) uiScalePage.close();

        if (target == Mode.ABOUT) aboutPage.open();

        // 时钟的"时间停没停"是跨帧累计的判断，离开时清掉
        if (this.mode == Mode.CLOCK) ClockPage.reset();

        if (target == Mode.UI_SCALE) uiScalePage.open();

        this.mode = target;
        settingsList.open();
    }

    public void back() {
        navigateTo(Mode.MAIN);
    }

    /**
     * 把图片文件拖进游戏窗口 —— 正开着某个会话时，等同于选了这张图发出去。
     *
     * 为什么值得有这一条：从相册选图的前提是那张图【已经在截图目录里】。而玩家想发的
     * 常常是刚从别处存下来的一张图，按现在的路子他得先把文件手动挪进 screenshots/，
     * 再开手机进相册翻出来。拖进来一步到位。
     *
     * 原版把窗口的拖放回调转给当前 Screen（MouseHandler.onDrop），所以这里只要覆写就行，
     * 不必自己碰 GLFW。
     *
     * 一次只收第一张图：上传本来就是一次一张（见 ChatImageSender），拖一叠进来时挑第一张
     * 比整批拒绝有用。不是图片的文件说一句就算了——玩家多半是拖错了窗口。
     */
    @Override
    public void onFilesDrop(List<Path> files) {
        // 表情页开着时，拖进来是"收进表情目录"而不是"发出去"：那一页的语境就是攒表情。
        // 这也是表情唯一的游戏内导入方式——弹系统文件选择器要 AWT，在 macOS 上与游戏抢主线程
        if (mode == Mode.CHAT_STICKER_PICKER) {
            importStickers(files);
            return;
        }

        UUID target = switch (mode) {
            case CHAT_CONVERSATION -> chatConversation.peer();
            // 选照片那一页也收：人已经在"挑一张"的语境里了，拖进来是同一个意思
            case CHAT_PHOTO_PICKER -> pendingConversationPeer;
            default -> null;
        };
        if (target == null) return;

        if (!ServerConfig.allowChatImages()) {
            tellPlayer("mcphone.chat.image_disabled");
            return;
        }
        Path picture = files.stream().filter(PhoneScreen::looksLikeImage).findFirst().orElse(null);
        if (picture == null) {
            tellPlayer("mcphone.chat.drop_not_image");
            return;
        }

        ChatImageSender.send(target, picture);
        if (mode == Mode.CHAT_PHOTO_PICKER) navigateTo(Mode.CHAT_CONVERSATION);
    }

    /**
     * 把拖进来的图片收进表情目录。
     *
     * 这里【收全部】而不是只收第一张：拖一整包表情进来是常事，而导入不像发送那样一次只能一个。
     * 复制文件在后台线程做，完了回主线程重扫目录——玩家看到的是它们一张张出现在格子里。
     */
    private void importStickers(List<Path> files) {
        List<Path> pictures = files.stream().filter(PhoneScreen::looksLikeImage).toList();
        if (pictures.isEmpty()) {
            tellPlayer("mcphone.chat.drop_not_image");
            return;
        }

        net.minecraft.Util.backgroundExecutor().execute(() -> {
            int imported = 0;
            for (Path picture : pictures) {
                if (StickerLibrary.importFrom(picture) != null) imported++;
            }
            final int done = imported;
            Minecraft.getInstance().execute(() -> {
                StickerLibrary.refresh();
                if (done > 0) tellPlayer("mcphone.chat.sticker_imported", done);
                else tellPlayer("mcphone.chat.sticker_import_failed");
            });
        });
    }

    /**
     * 挑好的那张：发出去，然后立刻回会话——玩家要看的是那条消息冒出来，
     * 压缩与上传都在后面自己走。没挑（点了翻页、点了空白）就什么都不做。
     */
    private void sendPicked(Path picked) {
        if (picked == null) return;
        ChatImageSender.send(pendingConversationPeer, picked);
        navigateTo(Mode.CHAT_CONVERSATION);
    }

    /**
     * 按扩展名判，不去读文件头。
     *
     * 真正能不能解码由 ImageIO 说了算（见 ImageCodec），这里只是别把一个拖错的
     * 存档或 jar 当成图片提交上去。这几种都是 ImageIO 自带解码器认得的。
     */
    private static boolean looksLikeImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif") || name.endsWith(".bmp");
    }

    private void tellPlayer(String translationKey, Object... args) {
        // 动作栏而不是聊天框：玩家的眼睛正看着手机屏幕
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(translationKey, args), true);
        }
    }

    /** 正开着与这个人的会话吗，收到消息时据此决定要不要弹通知 */
    public boolean isViewingConversation(UUID peer) {
        return mode == Mode.CHAT_CONVERSATION && chatConversation.isViewing(peer);
    }

    /**
     * 点开一个 App：先问 openPage()，没有就走 onPress() 由它自己跳出去。
     *
     * 公开是为了快捷键：{@link AppHotkeyHandler} 先开机再调这一句，走的必须是
     * 与点图标【同一条】路——附属那一页的 onOpen/onClose 配对、异常兜底、
     * 主屏与 ADDON_PAGE 之间的模式切换都在这里面，另写一条迟早两边不一样。
     */
    public void launchApp(IPhoneApp app) {
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

    /** 先清字段再回调 onClose()：它里面可能又开一页，后清会把新页抹掉 */
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

    /** 调一次页面回调；抛异常就当场关掉退回主屏，留着会每帧再抛。出异常时算没处理 */
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

    /** 画附属那一页，给它的是扣掉状态栏与导航栏的内容区 */
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

    /** 导航栏 ◁ 走这里；ESC 不退层而是直接关机，见 {@link #keyPressed}。真的退了一层才 true */
    private boolean goBackOneLevel() {
        if (mode == Mode.GALLERY && gallery.backToGrid()) return true;

        if (mode == Mode.DEVICE_NAME) {
            navigateTo(Mode.SETTINGS);
            return true;
        }

        // 放大看的那张图先关掉：那不是一页，但它盖住了整块内容区，返回键该先收它
        if (mode == Mode.CHAT_CONVERSATION && chatConversation.dismissViewer()) return true;

        // 选照片、选表情都是从某个会话点进来的，返回自然回那个会话
        if (mode == Mode.CHAT_PHOTO_PICKER || mode == Mode.CHAT_STICKER_PICKER) {
            navigateTo(Mode.CHAT_CONVERSATION);
            return true;
        }

        if (mode == Mode.CHAT_ADD_CONTACT || mode == Mode.CHAT_CONVERSATION) {
            navigateTo(Mode.CHAT);
            return true;
        }

        if (mode == Mode.NOTE_EDIT) {
            navigateTo(Mode.NOTES);
            return true;
        }

        if (mode == Mode.ADDON_PAGE) {
            if (callPage(IPhonePage::onBack)) return true;
            navigateTo(Mode.MAIN);
            return true;
        }

        if (mode == Mode.APP_DETAIL || mode == Mode.COMPANION_APPS) {
            navigateTo(Mode.APP_STORE);
            return true;
        }

        if (mode == Mode.APP_MANAGER_DETAIL) {
            navigateTo(Mode.APP_MANAGER);
            return true;
        }

        // 设置的子页一律退回设置，而不是弹回主屏。
        // 原来只有「设备命名」与「关于」是这么走的，壁纸、字体颜色、App 管理器都直接
        // 回主屏——同一层的五项里三项走一条路、两项走另一条，玩家改完壁纸想接着改字色，
        // 得从主屏重新点进设置
        if (mode == Mode.ABOUT || mode == Mode.WALLPAPER_PICKER
                || mode == Mode.FONT_COLOR_PICKER || mode == Mode.UI_SCALE
                || mode == Mode.APP_MANAGER) {
            navigateTo(Mode.SETTINGS);
            return true;
        }

        if (mode != Mode.MAIN) {
            navigateTo(Mode.MAIN);
            return true;
        }
        return false;
    }

    private void computeLayout() {
        if (!layoutDirty) return;

        final int phoneW = PhoneTheme.PHONE_TOTAL_WIDTH;
        final int phoneH = PhoneTheme.PHONE_TOTAL_HEIGHT;

        this.phoneLeft = (this.width - phoneW) / 2 + PhoneTheme.PHONE_BORDER;
        this.phoneTop = (this.height - phoneH) / 2 + PhoneTheme.PHONE_BORDER + PhoneTheme.SCREEN_Y_OFFSET;

        this.layoutDirty = false;
    }

    private void invalidateLayout() { layoutDirty = true; }

    @Override
    protected void init() {
        super.init();
        invalidateLayout();

        if (!sessionResumed) {
            sessionResumed = true;
            resumeSession();
        }
    }

    /** 续开上次关机时停的那一页，白名单与有效性由 {@link PhoneSession} 把关 */
    private void resumeSession() {
        Mode target = PhoneSession.resumeMode();
        if (target == null || target == Mode.MAIN) return;

        pendingConversationPeer = PhoneSession.resumePeer();
        navigateTo(target);
    }

    @Override
    public void resize(Minecraft mc, int w, int h) { super.resize(mc, w, h); invalidateLayout(); }

    @Override
    public void render(GuiGraphics g, int rawMouseX, int rawMouseY, float partialTick) {
        this.nowMs = System.currentTimeMillis();
        computeLayout();

        // 1.20.1 的 renderBackground 只收 GuiGraphics；1.21 多了鼠标坐标与 partialTick。
        // 那三个参数在这里本来也用不上：它铺的是整个窗口，不在手机那层缩放里
        renderBackground(g);

        // 从这里往下，所有页面拿到的都是换算过的坐标——它们按 120×200 算命中，
        // 而屏幕上画出来的是放大过的
        final int mouseX = (int) Math.round(unscaledX(rawMouseX));
        final int mouseY = (int) Math.round(unscaledY(rawMouseY));

        float scale = renderScale();
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
                    nowMs, mouseX, mouseY, partialTick);
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
            case UI_SCALE          -> uiScalePage.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, font, this.width, this.height);
            case APP_MANAGER       -> appManagerPage.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, partialTick, font);
            case APP_MANAGER_DETAIL -> appManagerDetail.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, partialTick, font);
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
            case ABOUT             -> aboutPage.render(g, phoneLeft, phoneTop,
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
            case CHAT_PHOTO_PICKER -> chatPhotoPicker.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, font);
            case CHAT_STICKER_PICKER -> chatStickerPicker.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, font);
            case NOTES             -> notesList.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT, mouseX, mouseY, font);
            case CLOCK             -> ClockPage.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT, font);
            case WEATHER           -> WeatherPage.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT, font);
            case READER            -> bookList.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, partialTick, font);
            case NOTE_EDIT         -> noteEditor.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, partialTick, font);
        }

        renderNavBar(g, mouseX, mouseY);

        // 外壳最后画，盖在不透明的状态栏与导航栏之上；仍在 pushPose 内，开机动画要一起缩放
        PhoneChassis.drawFrame(g, phoneLeft, phoneTop);

        g.pose().popPose();

    }

    private void renderScreenBackground(GuiGraphics g) {
        PhoneChassis.drawScreenBackground(g, phoneLeft, phoneTop);
    }

    private void renderStatusBar(GuiGraphics g) {
        PhoneChassis.drawStatusBar(g, font, phoneLeft, phoneTop);
    }

    /** 建一次就够。标签在这一刻定死；PhoneScreen 每次开机都是新造的，换语言重开就跟上 */
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
                Component.translatable("mcphone.settings.ui_scale").getString(),
                () -> navigateTo(Mode.UI_SCALE),
                () -> PhoneScale.percent() + "%"));
        settingItems.add(new SettingsList.Item(
                Component.translatable("mcphone.settings.device_name").getString(),
                () -> navigateTo(Mode.DEVICE_NAME),
                this::currentDeviceNameLabel));
        settingItems.add(new SettingsList.Item(
                Component.translatable("mcphone.app.app_manager").getString(),
                () -> navigateTo(Mode.APP_MANAGER),
                () -> String.valueOf(PhoneScreenRegistry.getAppCount())));
        settingItems.add(new SettingsList.Item(
                Component.translatable("mcphone.gui.about").getString(),
                () -> navigateTo(Mode.ABOUT)));

        settingsList.setItems(settingItems);
    }

    private String currentDeviceNameLabel() {
        if (minecraft == null || minecraft.player == null) return "";
        String name = com.november.mcphone.core.PhoneItemData
                .getDeviceName(location.resolve(minecraft.player));
        return (name == null || name.isBlank())
                ? Component.translatable("mcphone.settings.device_name_unset").getString()
                : name;
    }

    private static String currentFontColorLabel() {
        return Component.translatable(FontPalette.current().translationKey()).getString();
    }

    private void renderNavBar(GuiGraphics g, int mouseX, int mouseY) {
        PhoneChassis.drawNavBar(g, font, phoneLeft, phoneTop, mouseX, mouseY);
    }

    /**
     * 这一帧整个手机要放大多少 —— 开场动画那一档乘玩家定的界面大小。
     *
     * 两者相乘而不是二选一：开机动画是"从 60% 弹到 100%"，界面大小是"100% 到底是多大"，
     * 各说各的一件事。乘起来之后动画照样是从小弹到大，只是终点变成了玩家定的那个尺寸。
     *
     * 鼠标坐标要按同一个数除回去，见 {@link #unscaledX}。
     */
    private float renderScale() {
        return getAnimationScale() * PhoneScale.effective(this.width, this.height);
    }

    private float getAnimationScale() {
        if (animationDone) return 1f;
        long elapsed = nowMs - openTimeMs;
        int dur = PhoneTheme.OPEN_ANIMATION_MS;
        if (elapsed >= dur) { animationDone = true; return 1f; }
        float t = (float) elapsed / dur;
        float c1 = 1.70158f, c3 = c1 + 1;
        return (float)(1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2)) * 0.4f + 0.6f;
    }

    /**
     * 撤掉缩放（开场动画的那一档 + 玩家定的界面大小）。结果仍是屏幕坐标，原点没挪到
     * 手机左上角，可以直接和 phoneLeft/phoneTop 比。
     *
     * 【所有】进到各页去的鼠标坐标都要先过这一道。各页是按 120×200 那个坐标系写的，
     * 界面放大之后画面变了、它们算命中的那套数没变，不换算的话点哪儿都不对。
     */
    private double unscaledX(double mx) {
        int cx = phoneLeft + PhoneTheme.PHONE_WIDTH / 2;
        return (mx - cx) / renderScale() + cx;
    }

    private double unscaledY(double my) {
        int cy = phoneTop + PhoneTheme.PHONE_HEIGHT / 2;
        return (my - cy) / renderScale() + cy;
    }

    /** 点击是否落在手机机身（含边框）内。收的是【换算过】的坐标 */
    private boolean isInsidePhone(double lx, double ly) {
        int fl = phoneLeft - PhoneTheme.PHONE_BORDER;
        int ft = phoneTop - PhoneTheme.PHONE_BORDER;
        return lx >= fl && lx < fl + PhoneTheme.PHONE_TOTAL_WIDTH
            && ly >= ft && ly < ft + PhoneTheme.PHONE_TOTAL_HEIGHT;
    }

    /**
     * 绑键界面正等着的话，收下这一下鼠标键；没在等就返回 false，什么都不做。
     *
     * 公开是给 {@link AppHotkeyHandler} 用的：它听的是 MouseHandler 一进门就发的
     * InputEvent.MouseButton.Pre，比 {@link #mouseClicked} 早得多，中间也不经过任何
     * 分发。绑键这件事走那条路最靠得住——理由见那边的注释。
     */
    public boolean captureHotkeyMouse(int button) {
        if (mode != Mode.APP_MANAGER_DETAIL || !appManagerDetail.isCapturingKey()) return false;
        appManagerDetail.captureMouse(button);
        return true;
    }

    @Override
    public boolean mouseClicked(double rawX, double rawY, int button) {
        // 绑键界面等着的话这一下归它。正常情况下轮不到这里——AppHotkeyHandler 在
        // 更早的地方就收走并取消了事件；留着是兜底：万一哪个模组把那条事件截了，
        // 屏幕这条路还在。两条都试过之后仍然绑不上，那就说明这一下压根没进游戏
        if (captureHotkeyMouse(button)) return true;

        if (button != 0) return super.mouseClicked(rawX, rawY, button);

        // 换算一次，下面全用它。super 那几句仍然给原始坐标：原版控件是按屏幕坐标摆的
        final double mx = unscaledX(rawX);
        final double my = unscaledY(rawY);

        // 点在机身外＝收起手机，哪一页都一样。判定必须在分发之前：
        // 各页的 mouseClicked 一律 yield true 把点击吞掉，放到后面就永远轮不到
        if (!isInsidePhone(mx, my)) {
            onClose();
            return true;
        }

        switch (PhoneChassis.hitTestNavBar(mx, my, phoneLeft, phoneTop)) {
            case BACK -> {
                // 主屏上按返回不关机
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
                if (homeGrid.mousePressed(mx, my)) yield true;

                // 机身外的已经在上面收走了，到这儿必是机身内的空白处：横着拖是翻页
                homeGrid.pressBlank(mx, my);
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
            case UI_SCALE -> {
                uiScalePage.mouseClicked(mx, my);
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
                IPhoneApp picked = appManagerPage.consumeSelection();
                if (picked != null) {
                    pendingManagedApp = picked;
                    navigateTo(Mode.APP_MANAGER_DETAIL);
                }
                yield true;
            }
            case APP_MANAGER_DETAIL -> {
                appManagerDetail.mouseClicked(mx, my, button);
                // 卸载完了那个 App 已经不在列表里，留在它的详情页上没有意义
                if (appManagerDetail.consumeBackRequest()) navigateTo(Mode.APP_MANAGER);
                yield true;
            }
            case MUSIC_PLAYER -> {
                musicPage.mouseClicked(mx, my, button);
                yield true;
            }
            case APP_STORE -> {
                appStore.mouseClicked(mx, my, button);
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
                // 不看返回值：附属页里的空点击不该关机
                callPage(p -> p.mouseClicked(mx, my, button));
                yield true;
            }
            case ABOUT, CLOCK, WEATHER -> {
                yield true;
            }
            case READER -> {
                bookList.mouseClicked(mx, my, button);
                BookRef book = bookList.consumeOpenRequest();
                // 打开之后接管屏幕的是那本书自己的界面，这一部手机就退下去了
                if (book != null) BookSources.open(book);
                yield true;
            }
            case APP_DETAIL -> {
                appDetail.mouseClicked(mx, my, button);
                // 顺序不能反：先 navigateTo 的话 reset 会把刷新请求清掉
                if (appDetail.consumeInstalledRequest()) appStore.onInstalled();
                if (appDetail.consumeBackRequest()) navigateTo(Mode.APP_STORE);
                yield true;
            }
            case GALLERY -> {
                gallery.mouseClicked(mx, my, button);
                yield true;
            }
            case DEVICE_NAME -> {
                if (deviceNameEditor.mouseClicked(mx, my, button)) navigateTo(Mode.SETTINGS);
                yield true;
            }
            case CHAT -> {
                chatList.mouseClicked(mx, my, button);
                // 点了传送就关机，包由列表自己发
                if (chatList.consumeCloseRequest()) {
                    onClose();
                    yield true;
                }
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

                ChatConversation.Attach attach = chatConversation.consumeAttachRequest();
                if (attach != null) {
                    // 先记下是谁：进挑东西那一页会 close 掉会话，对端就没了
                    pendingConversationPeer = chatConversation.peer();
                    navigateTo(switch (attach) {
                        case IMAGE -> Mode.CHAT_PHOTO_PICKER;
                        case STICKER -> Mode.CHAT_STICKER_PICKER;
                    });
                }
                yield true;
            }
            case CHAT_PHOTO_PICKER -> {
                chatPhotoPicker.mouseClicked(mx, my, button);
                sendPicked(chatPhotoPicker.consumeSelection());
                yield true;
            }
            case CHAT_STICKER_PICKER -> {
                chatStickerPicker.mouseClicked(mx, my, button);
                sendPicked(chatStickerPicker.consumeSelection());
                yield true;
            }
            case NOTES -> {
                notesList.mouseClicked(mx, my, button);
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
                if (noteEditor.consumeBackRequest()) navigateTo(Mode.NOTES);
                yield true;
            }
        };
    }

    @Override
    public boolean mouseDragged(double rawX, double rawY, int button, double dx, double dy) {
        final double mx = unscaledX(rawX);
        final double my = unscaledY(rawY);
        // 位移也要除：拖一段真实距离，在放大的界面里对应的手机内距离要小一些
        final double scale = renderScale();
        final double ldx = dx / scale;
        final double ldy = dy / scale;

        if (mode == Mode.MAIN && button == 0 && homeGrid.mouseDragged(mx, my)) {
            return true;
        }

        if (mode == Mode.UI_SCALE && uiScalePage.mouseDragged(mx)) return true;

        // 多行输入框靠拖动选中文本，不转发的话选不了
        if (mode == Mode.NOTE_EDIT && noteEditor.mouseDragged(mx, my, button, ldx, ldy)) return true;
        return super.mouseDragged(rawX, rawY, button, dx, dy);
    }

    /** 松手才定性：主屏上这一下算"点开"还是"挪位置" */
    @Override
    public boolean mouseReleased(double rawX, double rawY, int button) {
        if (mode == Mode.UI_SCALE) uiScalePage.mouseReleased();

        if (mode == Mode.MAIN && button == 0
                && homeGrid.mouseReleased(unscaledX(rawX), unscaledY(rawY))) {
            IPhoneApp launch = homeGrid.consumeLaunchRequest();
            if (launch != null) launchApp(launch);
            return true;
        }
        return super.mouseReleased(rawX, rawY, button);
    }

    /**
     * 1.21 的 mouseScrolled 是四参（横、竖两个滚动量），1.20.1 是三参、只有竖直。
     * 手机界面从来只用竖直滚动，所以把 scrollY 收成第三个参数即可，功能不丢。
     *
     * 【签名必须对得上】：写成四参不会报错，只是【永远不会被调到】——
     * 那是一个静默失效，滚不动却查不出原因。@Override 是这里唯一的守卫。
     */
    @Override
    public boolean mouseScrolled(double rawX, double rawY, double scrollY) {
        final double mx = unscaledX(rawX);
        final double my = unscaledY(rawY);

        if (mode == Mode.MAIN && homeGrid.mouseScrolled(scrollY)) return true;
        if (mode == Mode.GALLERY && gallery.mouseScrolled(scrollY)) return true;
        if (mode == Mode.CHAT && chatList.mouseScrolled(scrollY)) return true;
        if (mode == Mode.CHAT_ADD_CONTACT && chatAddContact.mouseScrolled(scrollY)) return true;
        if (mode == Mode.CHAT_CONVERSATION && chatConversation.mouseScrolled(scrollY)) return true;
        if (mode == Mode.CHAT_PHOTO_PICKER && chatPhotoPicker.mouseScrolled(scrollY)) return true;
        if (mode == Mode.CHAT_STICKER_PICKER && chatStickerPicker.mouseScrolled(scrollY)) return true;
        if (mode == Mode.NOTES && notesList.mouseScrolled(scrollY)) return true;
        if (mode == Mode.READER && bookList.mouseScrolled(scrollY)) return true;
        if (mode == Mode.MUSIC_PLAYER && musicPage.mouseScrolled(scrollY, my)) return true;
        if (mode == Mode.NOTE_EDIT && noteEditor.mouseScrolled(mx, my, scrollY)) return true;
        // 设置那几页：1.9.1 之前一页都滚不动，内容超出一屏就再也看不到
        if (mode == Mode.WALLPAPER_PICKER && wallpaperPicker.mouseScrolled(scrollY)) return true;
        if (mode == Mode.APP_MANAGER && appManagerPage.mouseScrolled(scrollY)) return true;
        if (mode == Mode.APP_MANAGER_DETAIL && appManagerDetail.mouseScrolled(scrollY, font)) return true;
        if (mode == Mode.ABOUT && aboutPage.mouseScrolled(scrollY, font)) return true;
        if (mode == Mode.ADDON_PAGE
                && callPage(p -> p.mouseScrolled(mx, my, scrollY))) return true;
        return super.mouseScrolled(rawX, rawY, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // App 管理页正等着玩家按一个键当快捷键。这一下不是在操作手机，是在绑键，
        // 所以要抢在下面 ESC 关机之前——在那一页上 ESC 的意思是"清除这个绑定"
        if (mode == Mode.APP_MANAGER_DETAIL && appManagerDetail.isCapturingKey()) {
            appManagerDetail.captureKey(keyCode, scanCode);
            return true;
        }
        if (keyCode == 256) { // ESC
            // ESC 一下直接关机，不退层；退层交给导航栏 ◁
            onClose();
            return true;
        }
        // 带输入框的界面要抢在背包键之前，且无论是否消费都吃掉按键：打拼音一定会按到 e
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
        // 书架顶上那条搜索栏一直握着焦点，这一页同样要整个吃掉按键
        if (mode == Mode.READER) {
            bookList.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        // 附属页面自称有输入框时同样要抢在背包键之前
        if (mode == Mode.ADDON_PAGE && pageCapturesKeyboard()) {
            callPage(p -> p.keyPressed(keyCode, scanCode, modifiers));
            return true;
        }

        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            if (mode != Mode.MAIN) back();
            else onClose();
            return true;
        }
        // 相册方向键放最后，免得有人把背包键绑成方向键时被相册吃掉
        if (mode == Mode.GALLERY && gallery.keyPressed(keyCode)) return true;
        if (mode == Mode.CHAT_PHOTO_PICKER && chatPhotoPicker.keyPressed(keyCode)) return true;
        if (mode == Mode.CHAT_STICKER_PICKER && chatStickerPicker.keyPressed(keyCode)) return true;
        if (mode == Mode.ADDON_PAGE
                && callPage(p -> p.keyPressed(keyCode, scanCode, modifiers))) return true;

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 字符输入。EditBox 靠这个收字符（含输入法提交与粘贴），是所有文字进入界面的唯一通道 */
    @Override
    public boolean charTyped(char c, int modifiers) {
        if (mode == Mode.ADDON_PAGE
                && callPage(p -> p.charTyped(c, modifiers))) return true;
        if (mode == Mode.DEVICE_NAME && deviceNameEditor.charTyped(c, modifiers)) return true;
        if (mode == Mode.CHAT_CONVERSATION && chatConversation.charTyped(c, modifiers)) return true;
        if (mode == Mode.NOTE_EDIT && noteEditor.charTyped(c, modifiers)) return true;
        if (mode == Mode.READER && bookList.charTyped(c, modifiers)) return true;
        return super.charTyped(c, modifiers);
    }

    @Override public void onClose() { super.onClose(); }

    /** 用 removed() 而不是 onClose()：被别的界面顶掉时 onClose 不触发 */
    @Override
    public void removed() {
        // 先记再关：下面这几个 close() 会把页面状态清掉
        PhoneSession.save(mode, pendingConversationPeer);

        if (mode == Mode.GALLERY) gallery.close();
        if (mode == Mode.CHAT_PHOTO_PICKER) chatPhotoPicker.close();
        if (mode == Mode.CHAT_STICKER_PICKER) chatStickerPicker.close();
        if (mode == Mode.CHAT_CONVERSATION) chatConversation.close();

        // 图片消息的贴图只在手机开着时有用。留到关机才放，是因为"会话 → 列表 → 会话"
        // 是常有的来回，每次都放掉等于每次回来重下一遍
        ChatImageCache.clear();
        if (mode == Mode.NOTE_EDIT) noteEditor.close();

        // 关手机、被顶掉、退出世界都不经过 navigateTo，IPhonePage.onClose() "一定会被调用"靠这一行兑现
        closeAddonPage();

        super.removed();
    }
    @Override public boolean isPauseScreen() { return false; }
}
