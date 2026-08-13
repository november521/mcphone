package com.november.mcphone.gui;

import com.november.mcphone.api.IPhoneApp;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

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
    public enum Mode { MAIN, SETTINGS, WALLPAPER_PICKER, APP_MANAGER, MUSIC_PLAYER, APP_STORE, GALLERY, DEVICE_NAME, CHAT, CHAT_ADD_CONTACT, CHAT_CONVERSATION }


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

    // ---- 相册 ----
    private final Gallery gallery = new Gallery();

    // ---- 设备名称 ----
    private final DeviceNameEditor deviceNameEditor = new DeviceNameEditor();

    // ---- 聊天 ----
    private final ChatList chatList = new ChatList();
    private final ChatAddContact chatAddContact = new ChatAddContact();
    private final ChatConversation chatConversation = new ChatConversation();

    /**
     * 待打开的会话对端。
     *
     * navigateTo 只认模式、不带参数，而进入会话必须知道是跟谁聊——
     * 用一个字段把 peer 递进去，别的界面都不需要这种东西，
     * 所以不值得为它改 navigateTo 的签名。
     */
    private UUID pendingConversationPeer;

    /**
     * 手机在玩家哪只手上。
     * 设备名要写回这只手上的物品堆——玩家两只手各拿一只手机时不能改错。
     */
    private final InteractionHand hand;

    // ---- 主屏幕 hover ----
    private int hoveredAppIndex = -1;

    // ---- 布局缓存 ----
    private int phoneLeft, phoneTop;
    private int gridStartX, gridStartY;
    private boolean layoutDirty = true;
    private long nowMs;

    public PhoneScreen() {
        this(InteractionHand.MAIN_HAND);
    }

    public PhoneScreen(InteractionHand hand) {
        super(Component.translatable("mcphone.gui.home"));
        this.hand = hand;
        this.openTimeMs = System.currentTimeMillis();
        this.animationDone = PhoneTheme.OPEN_ANIMATION_MS <= 0;
    }

    // ============================================================
    //  导航
    // ============================================================

    public void navigateTo(Mode target) {
        if (this.mode == target) return;

        // 离开商店时清掉上次的列表与提示，下次进入重新拉取
        if (this.mode == Mode.APP_STORE && target != Mode.APP_STORE) appStore.reset();

        // 相册进出都要动作：进入时重扫目录，离开时释放缩略图贴图
        if (this.mode == Mode.GALLERY) gallery.close();
        if (target == Mode.GALLERY) gallery.open();

        // 进入命名界面时把当前设备名填进输入框
        if (this.mode == Mode.DEVICE_NAME) deviceNameEditor.close();
        if (target == Mode.DEVICE_NAME) deviceNameEditor.open(hand);

        // 会话列表离开即停止定时刷新，不在后台空转
        if (this.mode == Mode.CHAT) chatList.close();
        if (target == Mode.CHAT) chatList.open();

        if (this.mode == Mode.CHAT_ADD_CONTACT) chatAddContact.close();
        if (target == Mode.CHAT_ADD_CONTACT) chatAddContact.open();

        // 进入会话即拉取历史消息，离开即释放——聊天记录不留在内存里空占着
        if (this.mode == Mode.CHAT_CONVERSATION) chatConversation.close();
        if (target == Mode.CHAT_CONVERSATION) chatConversation.open(pendingConversationPeer);

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
            case GALLERY           -> renderGallery(g, mouseX, mouseY);
            case DEVICE_NAME       -> renderDeviceName(g, mouseX, mouseY, partialTick);
            case CHAT              -> renderChat(g, mouseX, mouseY);
            case CHAT_ADD_CONTACT  -> renderChatAddContact(g, mouseX, mouseY);
            case CHAT_CONVERSATION -> renderChatConversation(g, mouseX, mouseY, partialTick);
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
        final var apps = PhoneScreenRegistry.getApps();
        final int is = PhoneTheme.APP_ICON_SIZE;
        final int sx = PhoneTheme.APP_GRID_SPACING_X;
        final int cols = PhoneTheme.APP_COLUMNS;
        final int cellW = is + sx;
        final int cellH = is + (int)(font.lineHeight * PhoneTheme.APP_NAME_SCALE) + 4;

        for (int i = 0; i < apps.size(); i++) {
            int ix = gridStartX + (i % cols) * cellW;
            int iy = gridStartY + (i / cols) * cellH;

            if (iy + is > phoneTop + PhoneTheme.PHONE_HEIGHT - PhoneTheme.NAV_BAR_HEIGHT) break;

            if (i == hoveredAppIndex) {
                g.fill(ix - 2, iy - 2, ix + is + 2, iy + is + 2, PhoneTheme.COLOR_APP_PRESSED);
            }

            IPhoneApp app = apps.get(i);
            app.renderIcon(g, ix, iy, is, 0);

            drawAppName(g, app.getDisplayName().getString(), ix, iy, is);
        }
    }

    private void drawAppName(GuiGraphics g, String name, int ix, int iy, int is) {
        float ns = PhoneTheme.APP_NAME_SCALE;
        int nw = font.width(name);
        int nx = ix + (is - (int)(nw * ns)) / 2;
        int ny = iy + is + 2;
        g.pose().pushPose();
        g.pose().translate(nx + nw * ns / 2f, ny, 0);
        g.pose().scale(ns, ns, 1f);
        g.pose().translate(-(nx + nw * ns / 2f), -ny, 0);
        g.drawString(font, name, nx, ny, PhoneTheme.FONT_COLOR_APP_NAME, false);
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
                () -> {
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.literal("§eMCphone v1.0.0 §7by november"), false);
            }
        }));
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
        String name = minecraft.player.getItemInHand(hand)
                .get(com.november.mcphone.ModDataComponents.DEVICE_NAME.get());
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
        final int cellH = is + (int)(font.lineHeight * PhoneTheme.APP_NAME_SCALE) + 4;

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
                    IPhoneApp app = PhoneScreenRegistry.getApp(hoveredAppIndex);
                    if (app != null) { app.onPress(); yield true; }
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
        };
    }

    // ============================================================
    //  滚轮
    // ============================================================

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (mode == Mode.GALLERY && gallery.mouseScrolled(scrollY)) return true;
        if (mode == Mode.CHAT && chatList.mouseScrolled(scrollY)) return true;
        if (mode == Mode.CHAT_ADD_CONTACT && chatAddContact.mouseScrolled(scrollY)) return true;
        if (mode == Mode.CHAT_CONVERSATION && chatConversation.mouseScrolled(scrollY)) return true;
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

        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            if (mode != Mode.MAIN) back();
            else onClose();
            return true;
        }
        // 相册的方向键翻页放在最后：ESC 与背包键优先，
        // 免得有人把背包键绑成方向键时被相册吃掉
        if (mode == Mode.GALLERY && gallery.keyPressed(keyCode)) return true;

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
        if (mode == Mode.DEVICE_NAME && deviceNameEditor.charTyped(c, modifiers)) return true;
        if (mode == Mode.CHAT_CONVERSATION && chatConversation.charTyped(c, modifiers)) return true;
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
        super.removed();
    }
    @Override public boolean isPauseScreen() { return false; }
}
