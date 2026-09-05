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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 手机主屏幕 GUI：管理各页面之间的导航（{@link Mode}）、分发输入、兜住附属页面的异常 */
public final class PhoneScreen extends Screen {

    public enum Mode { MAIN, SETTINGS, WALLPAPER_PICKER, FONT_COLOR_PICKER, APP_MANAGER, MUSIC_PLAYER, APP_STORE, APP_DETAIL, COMPANION_APPS, ADDON_PAGE, ABOUT, GALLERY, DEVICE_NAME, CHAT, CHAT_ADD_CONTACT, CHAT_CONVERSATION, CHAT_PHOTO_PICKER, CHAT_STICKER_PICKER, NOTES, NOTE_EDIT, CLOCK, WEATHER, READER }

    private final long openTimeMs;
    private boolean animationDone;

    private Mode mode = Mode.MAIN;
    private final WallpaperPicker wallpaperPicker = new WallpaperPicker();
    private final FontColorPicker fontColorPicker = new FontColorPicker();

    private final SettingsList settingsList = new SettingsList();
    private final List<SettingsList.Item> settingItems = new ArrayList<>();

    private final AppManagerPage appManagerPage = new AppManagerPage();

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

        if (target == Mode.WALLPAPER_PICKER) WallpaperStore.refresh();

        if (this.mode == Mode.NOTES) notesList.close();
        if (target == Mode.NOTES) notesList.open();

        // 每次进书架都重扫一遍书源，理由见 BookList.open()
        if (this.mode == Mode.READER) bookList.close();
        if (target == Mode.READER) bookList.open();
        if (target == Mode.APP_MANAGER) appManagerPage.open();

        // 离开音乐页不停音乐，close 只收界面
        if (this.mode == Mode.MUSIC_PLAYER) musicPage.close();
        if (target == Mode.MUSIC_PLAYER) musicPage.open();

        if (this.mode == Mode.NOTE_EDIT) noteEditor.close();

        if (this.mode == Mode.FONT_COLOR_PICKER) fontColorPicker.close();

        // 时钟的"时间停没停"是跨帧累计的判断，离开时清掉
        if (this.mode == Mode.CLOCK) ClockPage.reset();

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
        if (ChatImageSender.isBusy()) {
            tellPlayer("mcphone.chat.image_too_fast");
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

    /** 点开一个 App：先问 openPage()，没有就走 onPress() 由它自己跳出去 */
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

    private String currentDeviceNameLabel() {
        if (minecraft == null || minecraft.player == null) return "";
        String name = location.resolve(minecraft.player)
                .get(com.november.mcphone.core.ModDataComponents.DEVICE_NAME.get());
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
     * 撤掉开场动画的缩放。结果仍是屏幕坐标，原点没挪到手机左上角，
     * 可以直接和 phoneLeft/phoneTop 比。
     */
    private double unscaledX(double mx) {
        int cx = phoneLeft + PhoneTheme.PHONE_WIDTH / 2;
        return (mx - cx) / getAnimationScale() + cx;
    }

    private double unscaledY(double my) {
        int cy = phoneTop + PhoneTheme.PHONE_HEIGHT / 2;
        return (my - cy) / getAnimationScale() + cy;
    }

    /** 点击是否落在手机机身（含边框）内 */
    private boolean isInsidePhone(double mx, double my) {
        double lx = unscaledX(mx);
        double ly = unscaledY(my);
        int fl = phoneLeft - PhoneTheme.PHONE_BORDER;
        int ft = phoneTop - PhoneTheme.PHONE_BORDER;
        return lx >= fl && lx < fl + PhoneTheme.PHONE_TOTAL_WIDTH
            && ly >= ft && ly < ft + PhoneTheme.PHONE_TOTAL_HEIGHT;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);

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
                double lx = unscaledX(mx);
                double ly = unscaledY(my);

                if (homeGrid.mousePressed(lx, ly)) yield true;

                // 机身外的已经在上面收走了，到这儿必是机身内的空白处：横着拖是翻页
                homeGrid.pressBlank(lx, ly);
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
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (mode == Mode.MAIN && button == 0
                && homeGrid.mouseDragged(unscaledX(mx), unscaledY(my))) {
            return true;
        }

        // 多行输入框靠拖动选中文本，不转发的话选不了
        if (mode == Mode.NOTE_EDIT && noteEditor.mouseDragged(mx, my, button, dx, dy)) return true;
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    /** 松手才定性：主屏上这一下算"点开"还是"挪位置" */
    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (mode == Mode.MAIN && button == 0
                && homeGrid.mouseReleased(unscaledX(mx), unscaledY(my))) {
            IPhoneApp launch = homeGrid.consumeLaunchRequest();
            if (launch != null) launchApp(launch);
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
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
        if (mode == Mode.NOTE_EDIT && noteEditor.mouseScrolled(mx, my, scrollX, scrollY)) return true;
        if (mode == Mode.ADDON_PAGE
                && callPage(p -> p.mouseScrolled(mx, my, scrollY))) return true;
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
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
