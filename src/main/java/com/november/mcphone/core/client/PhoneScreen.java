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
import com.november.mcphone.feature.music.client.MusicPlayer;
import com.november.mcphone.feature.notes.client.NoteEditor;
import com.november.mcphone.feature.notes.client.NotesList;
import com.november.mcphone.feature.settings.client.AboutPage;
import com.november.mcphone.feature.settings.client.DeviceNameEditor;
import com.november.mcphone.feature.settings.client.WallpaperPicker;
import com.november.mcphone.feature.store.client.AppDetail;
import com.november.mcphone.feature.store.client.AppStore;
import com.november.mcphone.feature.store.client.CompanionApps;
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
    public enum Mode { MAIN, SETTINGS, WALLPAPER_PICKER, APP_MANAGER, MUSIC_PLAYER, APP_STORE, APP_DETAIL, COMPANION_APPS, ADDON_PAGE, ABOUT, GALLERY, DEVICE_NAME, CHAT, CHAT_ADD_CONTACT, CHAT_CONVERSATION, NOTES, NOTE_EDIT }


    // ---- 打开动画 ----
    private final long openTimeMs;
    private boolean animationDone;

    // ---- 模式 ----
    private Mode mode = Mode.MAIN;
    private final WallpaperPicker wallpaperPicker = new WallpaperPicker();

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
    private final List<IPhoneApp> appManagerApps = new ArrayList<>();
    private int appManagerHover = -1;

    // ---- 音乐播放器 ----
    private final MusicPlayer musicPlayer = new MusicPlayer();

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
    private int hoveredAppIndex = -1;

    // ---- 主屏拖动排序 ----
    //
    // 按下【不】立即开 App：这一下可能是要把图标拖去别的格子。按下只记下是哪一格，
    // 真正开 App 推迟到 mouseReleased，位移没超过阈值才算一次点击。
    // 这是"能拖"必然要付的代价——按下的那一刻还看不出玩家想干什么。

    /** 按下时命中的图标下标，-1 表示这次按下没落在图标上 */
    private int pressedAppIndex = -1;

    /** 按下点的手机局部坐标，用来量挪了多远、够不够算拖动 */
    private double pressLocalX, pressLocalY;

    /** 位移超过 {@link PhoneTheme#APP_DRAG_THRESHOLD} 之后才为 true */
    private boolean draggingApp;

    /** 拖动中鼠标所在的局部坐标，浮起的那张图标画在这儿 */
    private double dragLocalX, dragLocalY;

    /** 拖动中松手会落到第几格 */
    private int dragTargetIndex = -1;

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

    // ============================================================
    //  导航
    // ============================================================

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
        // onClose() 一定会被调到——返回键、ESC、关手机、断线，全从 navigateTo 过
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
        if (this.mode == Mode.NOTES) notesList.close();
        if (target == Mode.NOTES) notesList.open();

        if (this.mode == Mode.NOTE_EDIT) noteEditor.close();

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

    /**
     * 返回上一层。
     *
     * ESC 与导航栏的 ◁ 共用这一套规则——分别实现的话，两条路的层级
     * 关系迟早会不一致：改了一处忘了另一处，玩家按 ESC 和点 ◁ 会去到
     * 不同的地方。
     *
     * @return 真的退了一层才返回 true；已在主屏返回 false，
     *         由调用方决定要不要关机（ESC 关，导航栏的 ◁ 不关）
     */

    // ============================================================
    //  附属 App 的页面
    // ============================================================
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

    // ============================================================
    //  布局
    // ============================================================

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

    // ============================================================
    //  渲染入口
    // ============================================================

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

        renderPhoneFrame(g);
        renderStatusBar(g);

        switch (mode) {
            case MAIN              -> renderAppGrid(g);
            case SETTINGS          -> renderSettingsList(g, mouseX, mouseY);
            case WALLPAPER_PICKER  -> renderWallpaperPicker(g, mouseX, mouseY);
            case APP_MANAGER       -> renderAppManager(g, mouseX, mouseY);
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
            case NOTE_EDIT         -> noteEditor.render(g, phoneLeft, phoneTop,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                    mouseX, mouseY, partialTick, font);
        }

        renderNavBar(g, mouseX, mouseY);
        g.pose().popPose();

        if (mode == Mode.MAIN)           updateAppHover(mouseX, mouseY);
        if (mode == Mode.SETTINGS)       updateSettingsHover(mouseX, mouseY);
        if (mode == Mode.APP_MANAGER)    updateAppManagerHover(mouseX, mouseY);
    }

    // ============================================================
    //  手机外壳 + 壁纸背景
    // ============================================================

    private void renderPhoneFrame(GuiGraphics g) {
        PhoneChassis.drawFrameAndWallpaper(g, phoneLeft, phoneTop);
    }

    // ============================================================
    //  状态栏
    // ============================================================

    private void renderStatusBar(GuiGraphics g) {
        PhoneChassis.drawStatusBar(g, font, phoneLeft, phoneTop);
    }

    // ============================================================
    //  App 网格
    // ============================================================

    private void renderAppGrid(GuiGraphics g) {
        final int is = PhoneTheme.APP_ICON_SIZE;
        final int cols = PhoneTheme.APP_COLUMNS;
        final int cellW = is + PhoneTheme.APP_GRID_SPACING_X;
        final int cellH = appCellHeight();

        // 拖动时按"抽出来、再插进去"的结果画，而不是画原顺序再叠个提示：
        // 玩家看到的直接就是松手后的样子，不必先松手再确认自己摆对没有
        List<IPhoneApp> ordered = new ArrayList<>(PhoneScreenRegistry.getApps());
        IPhoneApp floatingApp = null;
        int floatingSlot = -1;
        if (draggingApp && pressedAppIndex >= 0 && pressedAppIndex < ordered.size()) {
            floatingApp = ordered.remove(pressedAppIndex);
            floatingSlot = Math.max(0, Math.min(dragTargetIndex, ordered.size()));
            ordered.add(floatingSlot, floatingApp);
        }

        for (int i = 0; i < ordered.size(); i++) {
            int ix = gridStartX + (i % cols) * cellW;
            int iy = gridStartY + (i / cols) * cellH;

            if (iy + is > phoneTop + PhoneTheme.PHONE_HEIGHT - PhoneTheme.NAV_BAR_HEIGHT) break;

            // 被拖的那一格只留个空槽——它本人跟着鼠标走，最后单独画
            if (i == floatingSlot) {
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

        // 浮起的那张放最后画，才会盖在别的图标上面而不是被它们盖住。
        // 以鼠标为中心，手指按住哪儿它就在哪儿，不会跟手偏出去半格
        if (floatingApp != null) {
            int fx = (int) dragLocalX - is / 2;
            int fy = (int) dragLocalY - is / 2;
            floatingApp.renderIcon(g, fx, fy, is, 0);
            drawAppName(g, floatingApp.getDisplayName().getString(), fx, fy, is);
        }
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

    /**
     * 鼠标落在主屏的第几格，用于拖动时决定松手插到哪儿。
     *
     * 越界一律夹到最近的合法格子，而不是返回"没有"：拖到图标区外面松手时，
     * 最符合直觉的结果是落在最近的那一格，而不是弹回原位当无事发生。
     */
    private int gridSlotAt(double lx, double ly, int count) {
        if (count <= 0) return -1;

        final int cellW = PhoneTheme.APP_ICON_SIZE + PhoneTheme.APP_GRID_SPACING_X;
        final int cellH = appCellHeight();

        int col = (int) Math.floor((lx - gridStartX) / (double) cellW);
        int row = (int) Math.floor((ly - gridStartY) / (double) cellH);
        col = Math.max(0, Math.min(col, PhoneTheme.APP_COLUMNS - 1));
        row = Math.max(0, row);

        return Math.max(0, Math.min(row * PhoneTheme.APP_COLUMNS + col, count - 1));
    }

    /**
     * 画图标下面那行名字，缩放后仍要对准图标中线。
     *
     * 先 translate 到目标位置再 scale，最后在原点画——而不是缩放一个
     * 非原点的坐标。原先的写法把缩放锚点放在文字中心、却仍从左端起笔，
     * 结果实际中心比图标中线偏右 nw×ns×(1-ns)/2 像素，名字越长偏得越多。
     * 这类错位只在缩放时出现，肉眼看着"就是有点歪"，很难想到是变换写反了。
     */
    private void drawAppName(GuiGraphics g, String name, int ix, int iy, int is) {
        float ns = PhoneTheme.APP_NAME_SCALE;
        float nw = font.width(name) * ns;   // 缩放【后】的实际宽度

        g.pose().pushPose();
        g.pose().translate(ix + (is - nw) / 2f, iy + is + 2, 0);
        g.pose().scale(ns, ns, 1f);
        g.drawString(font, name, 0, 0, PhoneTheme.FONT_COLOR_APP_NAME, false);
        g.pose().popPose();
    }

    // ============================================================
    //  设置列表
    // ============================================================

    private void buildSettingItems() {
        if (!settingItems.isEmpty()) return;
        settingItems.add(new SettingItem(
                Component.translatable("mcphone.gui.wallpaper").getString(),
                () -> navigateTo(Mode.WALLPAPER_PICKER)));
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
        g.drawString(font, title, x, y, PhoneTheme.FONT_COLOR_TITLE, true);
        y += font.lineHeight + 4;

        // 分割线
        g.fill(x, y, x + w, y + 1, 0x44FFFFFF);
        y += 4;

        for (int i = 0; i < settingItems.size(); i++) {
            if (y + font.lineHeight + 6 > bottom) break;

            SettingItem item = settingItems.get(i);
            int rowH = font.lineHeight + 4;

            if (i == hoveredSettingIdx) {
                g.fill(x, y, x + w, y + rowH, 0x33FFFFFF);
            }

            g.drawString(font, item.label(), x + 2, y + 2, 0xFFCCCCCC, false);

            // 有当前值就显示值，否则画右箭头
            String right = item.value() != null ? item.value().get() : ">";
            // 值过长会与左侧标题撞上，按剩余宽度截断
            int maxRightW = w - font.width(item.label()) - 10;
            if (font.width(right) > maxRightW) {
                right = font.plainSubstrByWidth(right, Math.max(6, maxRightW - 4)) + "…";
            }
            int ax = x + w - font.width(right) - 4;
            g.drawString(font, right, ax, y + 2, 0xFF888888, false);

            y += rowH + 2;
        }

        // 空列表提示
        if (settingItems.isEmpty()) {
            String noItems = Component.translatable("mcphone.gui.no_settings").getString();
            g.drawString(font, noItems, x, y, 0xFF888888, false);
        }
    }

    // ============================================================
    //  App 管理器
    // ============================================================

    /**
     * 重建 App 管理器列表：列出全部已安装 App。
     * 系统 App 也在列表内，渲染时标灰且不响应点击。
     */
    private void refreshAppManagerList() {
        appManagerApps.clear();
        appManagerApps.addAll(PhoneScreenRegistry.getApps());
    }

    private void renderAppManager(GuiGraphics g, int mx, int my) {
        refreshAppManagerList();

        int y = phoneTop + PhoneTheme.STATUS_BAR_HEIGHT + 4;
        int x = phoneLeft + 6;
        int w = PhoneTheme.PHONE_WIDTH - 12;
        int bottom = phoneTop + PhoneTheme.PHONE_HEIGHT - PhoneTheme.NAV_BAR_HEIGHT;

        String title = Component.translatable("mcphone.app.app_manager").getString();
        g.drawString(font, title, x, y, PhoneTheme.FONT_COLOR_TITLE, true);
        y += font.lineHeight + 4;

        g.fill(x, y, x + w, y + 1, 0x44FFFFFF);
        y += 4;

        if (appManagerApps.isEmpty()) {
            g.drawString(font, Component.translatable("mcphone.gui.app_manager_empty").getString(),
                    x, y, 0xFF888888, false);
            return;
        }

        final String uninstall = Component.translatable("mcphone.gui.uninstall").getString();
        final String systemTag = Component.translatable("mcphone.gui.system_app").getString();

        for (int i = 0; i < appManagerApps.size(); i++) {
            if (y + font.lineHeight + 6 > bottom) break;
            IPhoneApp app = appManagerApps.get(i);
            int rowH = font.lineHeight + 4;
            boolean system = app.isSystemApp();

            // 系统 App 不可卸载，不画 hover 高亮
            if (i == appManagerHover && !system) {
                g.fill(x, y, x + w, y + rowH, 0x44FF4444);
            }

            g.drawString(font, app.getDisplayName().getString(), x + 2, y + 2,
                    system ? 0xFF666666 : 0xFFCCCCCC, false);

            String tag = system ? systemTag : uninstall;
            int tx = x + w - font.width(tag) - 4;
            g.drawString(font, tag, tx, y + 2, system ? 0xFF666666 : 0xFFFF6666, false);

            y += rowH + 2;
        }
    }

    private void updateAppManagerHover(int mx, int my) {
        int y = phoneTop + PhoneTheme.STATUS_BAR_HEIGHT + 4 + font.lineHeight + 4 + 4;
        int x = phoneLeft + 6;
        int w = PhoneTheme.PHONE_WIDTH - 12;

        appManagerHover = -1;
        for (int i = 0; i < appManagerApps.size(); i++) {
            int rowH = font.lineHeight + 4;
            // 系统 App 行不可选中；但 y 仍需累加，否则后续行的命中区会整体错位
            if (!appManagerApps.get(i).isSystemApp()
                    && mx >= x && mx <= x + w && my >= y && my <= y + rowH) {
                appManagerHover = i;
                return;
            }
            y += rowH + 2;
        }
    }

    // ============================================================
    //  壁纸选择器
    // ============================================================

    private void renderWallpaperPicker(GuiGraphics g, int mx, int my) {
        wallpaperPicker.render(g, phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                mx, my, font);
    }

    // ============================================================
    //  音乐播放器
    // ============================================================

    private void renderMusicPlayer(GuiGraphics g, int mx, int my) {
        musicPlayer.render(g, phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                mx, my, font);
    }

    // ============================================================
    //  应用商店
    // ============================================================

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

    // ============================================================
    //  设备名称
    // ============================================================

    private void renderDeviceName(GuiGraphics g, int mx, int my, float partialTick) {
        deviceNameEditor.render(g, phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                mx, my, partialTick, font);
    }

    // ============================================================
    //  聊天
    // ============================================================

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

    // ============================================================
    //  相册
    // ============================================================

    private void renderGallery(GuiGraphics g, int mx, int my) {
        gallery.render(g, phoneLeft, phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                mx, my, font);
    }

    // ============================================================
    //  底部导航栏
    // ============================================================

    private void renderNavBar(GuiGraphics g, int mouseX, int mouseY) {
        PhoneChassis.drawNavBar(g, font, phoneLeft, phoneTop, mouseX, mouseY);
    }

    // ============================================================
    //  动画
    // ============================================================

    private float getAnimationScale() {
        if (animationDone) return 1f;
        long elapsed = nowMs - openTimeMs;
        int dur = PhoneTheme.OPEN_ANIMATION_MS;
        if (elapsed >= dur) { animationDone = true; return 1f; }
        float t = (float) elapsed / dur;
        float c1 = 1.70158f, c3 = c1 + 1;
        return (float)(1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2)) * 0.4f + 0.6f;
    }

    // ============================================================
    //  坐标换算
    // ============================================================

    /**
     * 把屏幕坐标逆变换回未缩放的手机局部坐标。
     * render() 在开场动画期间以手机中心为原点做了缩放，
     * 因此命中判定必须应用同样的逆变换，否则动画期间点击位置会偏。
     */
    private double toLocalX(double mx) {
        int cx = phoneLeft + PhoneTheme.PHONE_WIDTH / 2;
        return (mx - cx) / getAnimationScale() + cx;
    }

    private double toLocalY(double my) {
        int cy = phoneTop + PhoneTheme.PHONE_HEIGHT / 2;
        return (my - cy) / getAnimationScale() + cy;
    }

    /**
     * 点击是否落在手机机身（含边框）内。
     * 矩形与 renderPhoneFrame() 绘制边框所用坐标一致，保证判定与视觉对齐。
     */
    private boolean isInsidePhone(double mx, double my) {
        double lx = toLocalX(mx);
        double ly = toLocalY(my);
        int fl = phoneLeft - PhoneTheme.PHONE_BORDER;
        int ft = phoneTop - PhoneTheme.PHONE_BORDER;
        return lx >= fl && lx < fl + PhoneTheme.PHONE_TOTAL_WIDTH
            && ly >= ft && ly < ft + PhoneTheme.PHONE_TOTAL_HEIGHT;
    }

    // ============================================================
    //  鼠标 hover
    // ============================================================

    private void updateAppHover(int mx, int my) {
        float s = getAnimationScale();
        int cx = phoneLeft + PhoneTheme.PHONE_WIDTH / 2;
        int cy = phoneTop + PhoneTheme.PHONE_HEIGHT / 2;
        int lx = (int)((mx - cx) / s + cx);
        int ly = (int)((my - cy) / s + cy);

        final var apps = PhoneScreenRegistry.getApps();
        final int is = PhoneTheme.APP_ICON_SIZE;
        final int cols = PhoneTheme.APP_COLUMNS;
        final int cellW = is + PhoneTheme.APP_GRID_SPACING_X;
        final int cellH = appCellHeight();

        hoveredAppIndex = -1;
        for (int i = 0; i < apps.size(); i++) {
            int ix = gridStartX + (i % cols) * cellW;
            int iy = gridStartY + (i / cols) * cellH;
            if (lx >= ix && lx <= ix + is && ly >= iy && ly <= iy + is + 6) {
                hoveredAppIndex = i;
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

    // ============================================================
    //  鼠标点击
    // ============================================================

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);

        // 导航栏抢在各模式之前：它在每个界面都存在，交给各 case 自己处理
        // 就得处处重复。各界面的内容都画在导航栏上方，不会争抢同一块区域
        switch (PhoneChassis.hitTestNavBar(mx, my, phoneLeft, phoneTop)) {
            case BACK -> {
                // 主屏上按返回不关机——真实手机就是这样，关机走 ESC 或点机身外
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
                if (hoveredAppIndex >= 0) {
                    // 先记着是哪一格，别急着开——这一下可能是要把它拖走。
                    // 到底算点开还是算挪位置，由 mouseReleased 定
                    pressedAppIndex = hoveredAppIndex;
                    pressLocalX = toLocalX(mx);
                    pressLocalY = toLocalY(my);
                    dragTargetIndex = pressedAppIndex;
                    draggingApp = false;
                    yield true;
                }
                // 只有点在手机机身外才关闭；机身内的空白处不响应
                if (!isInsidePhone(mx, my)) onClose();
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
            case APP_MANAGER -> {
                if (appManagerHover >= 0 && appManagerHover < appManagerApps.size()) {
                    IPhoneApp toUninstall = appManagerApps.get(appManagerHover);
                    if (!toUninstall.isSystemApp()) {
                        PhoneScreenRegistry.uninstall(toUninstall.getId());
                        refreshAppManagerList();
                        // 重置 hover：卸载后该索引会指向顶上来的另一个 App
                        appManagerHover = -1;
                    }
                }
                yield true;
            }
            case MUSIC_PLAYER -> {
                if (musicPlayer.mouseClicked(mx, my, button)) yield true;
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
            case ABOUT -> {
                // 这一页只有信息，没有可点的东西。仍然 yield true 把点击吃掉，
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

    // ============================================================
    //  拖动 / 松手 / 滚轮
    // ============================================================

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        // 主屏拖动排序。没过阈值之前什么都不做，好让这一下还有机会被当成点击
        if (mode == Mode.MAIN && button == 0 && pressedAppIndex >= 0) {
            double lx = toLocalX(mx);
            double ly = toLocalY(my);

            if (!draggingApp) {
                if (Math.abs(lx - pressLocalX) < PhoneTheme.APP_DRAG_THRESHOLD
                        && Math.abs(ly - pressLocalY) < PhoneTheme.APP_DRAG_THRESHOLD) {
                    return true;
                }
                draggingApp = true;
            }

            dragLocalX = lx;
            dragLocalY = ly;
            dragTargetIndex = gridSlotAt(lx, ly, PhoneScreenRegistry.getAppCount());
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
        if (mode == Mode.GALLERY && gallery.mouseScrolled(scrollY)) return true;
        if (mode == Mode.CHAT && chatList.mouseScrolled(scrollY)) return true;
        if (mode == Mode.CHAT_ADD_CONTACT && chatAddContact.mouseScrolled(scrollY)) return true;
        if (mode == Mode.CHAT_CONVERSATION && chatConversation.mouseScrolled(scrollY)) return true;
        if (mode == Mode.NOTES && notesList.mouseScrolled(scrollY)) return true;
        if (mode == Mode.NOTE_EDIT && noteEditor.mouseScrolled(mx, my, scrollX, scrollY)) return true;
        if (mode == Mode.ADDON_PAGE
                && callPage(p -> p.mouseScrolled(mx, my, scrollY))) return true;
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    // ============================================================
    //  键盘
    // ============================================================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            // 已在主屏时 goBackOneLevel 返回 false，此时 ESC 关机
            if (!goBackOneLevel()) onClose();
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
