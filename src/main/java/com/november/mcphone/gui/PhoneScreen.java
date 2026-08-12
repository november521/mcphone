package com.november.mcphone.gui;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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
    public enum Mode { MAIN, SETTINGS, WALLPAPER_PICKER, APP_MANAGER, MUSIC_PLAYER, APP_STORE }

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    // ---- 打开动画 ----
    private final long openTimeMs;
    private boolean animationDone;

    // ---- 模式 ----
    private Mode mode = Mode.MAIN;
    private final WallpaperPicker wallpaperPicker = new WallpaperPicker();

    // ---- 设置列表项 ----
    private static final record SettingItem(String label, Runnable action) {}
    private final List<SettingItem> settingItems = new ArrayList<>();
    private int hoveredSettingIdx = -1;

    // ---- App 管理器 ----
    private final List<IPhoneApp> appManagerApps = new ArrayList<>();
    private int appManagerHover = -1;

    // ---- 音乐播放器 ----
    private final MusicPlayer musicPlayer = new MusicPlayer();

    // ---- 应用商店 ----
    private final AppStore appStore = new AppStore();

    // ---- 主屏幕 hover ----
    private int hoveredAppIndex = -1;

    // ---- 布局缓存 ----
    private int phoneLeft, phoneTop;
    private int gridStartX, gridStartY;
    private boolean layoutDirty = true;
    private long nowMs;

    public PhoneScreen() {
        super(Component.translatable("mcphone.gui.home"));
        this.openTimeMs = System.currentTimeMillis();
        this.animationDone = PhoneTheme.OPEN_ANIMATION_MS <= 0;
    }

    // ============================================================
    //  导航
    // ============================================================

    public void navigateTo(Mode target) {
        // 离开商店时清掉上次的列表与提示，下次进入重新拉取
        if (this.mode == Mode.APP_STORE && target != Mode.APP_STORE) appStore.reset();
        this.mode = target;
        this.hoveredSettingIdx = -1;
    }

    public void back() {
        navigateTo(Mode.MAIN);
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
        }

        renderNavBar(g);
        g.pose().popPose();

        if (mode == Mode.MAIN)           updateAppHover(mouseX, mouseY);
        if (mode == Mode.SETTINGS)       updateSettingsHover(mouseX, mouseY);
        if (mode == Mode.APP_MANAGER)    updateAppManagerHover(mouseX, mouseY);
    }

    // ============================================================
    //  手机外壳 + 壁纸背景
    // ============================================================

    private void renderPhoneFrame(GuiGraphics g) {
        final int fl = phoneLeft - PhoneTheme.PHONE_BORDER;
        final int ft = phoneTop - PhoneTheme.PHONE_BORDER;
        final int fw = PhoneTheme.PHONE_TOTAL_WIDTH;
        final int fh = PhoneTheme.PHONE_TOTAL_HEIGHT;

        g.fill(fl, ft, fl + fw, ft + fh, PhoneTheme.COLOR_FRAME);
        g.fill(fl, ft, fl + fw, ft + 2, PhoneTheme.COLOR_FRAME_HIGHLIGHT);

        // ---- 壁纸或纯色背景 ----
        String wpName = NetworkHandler.WakeholderData.get();
        WallpaperStore.WallpaperEntry wp = WallpaperStore.findEntry(wpName);

        if (wp != null) {
            // 使用图片原始尺寸做 blit：按比例填充屏幕
            int texW = wp.imageWidth();
            int texH = wp.imageHeight();
            // 计算 cover 缩放：覆盖整个屏幕，超出部分裁剪
            float sw = (float) PhoneTheme.PHONE_WIDTH / texW;
            float sh = (float) PhoneTheme.PHONE_HEIGHT / texH;
            float s = Math.max(sw, sh);
            int srcW = (int)(PhoneTheme.PHONE_WIDTH / s);
            int srcH = (int)(PhoneTheme.PHONE_HEIGHT / s);
            int srcX = (texW - srcW) / 2;
            int srcY = (texH - srcH) / 2;

            // 参数顺序按 GuiGraphics 的 11 参重载：
            //   (贴图, x, y, 目标宽, 目标高, u, v, 源区宽, 源区高, 纹理宽, 纹理高)
            // 目标宽高在前、UV 在后，写反会导致目标矩形取到 srcX/srcY，
            // 而居中裁剪下二者必有一个为 0，壁纸就整个画不出来
            g.blit(wp.texture(),
                    phoneLeft, phoneTop,                              // 屏幕目标位置
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,  // 目标宽高
                    srcX, srcY,                                       // 纹理源 UV
                    srcW, srcH,                                       // 源区域宽高
                    texW, texH);                                      // 纹理总宽高
        } else {
            g.fill(phoneLeft, phoneTop,
                    phoneLeft + PhoneTheme.PHONE_WIDTH,
                    phoneTop + PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.COLOR_SCREEN_BG);
        }
    }

    // ============================================================
    //  状态栏
    // ============================================================

    private void renderStatusBar(GuiGraphics g) {
        g.fill(phoneLeft, phoneTop,
                phoneLeft + PhoneTheme.PHONE_WIDTH,
                phoneTop + PhoneTheme.STATUS_BAR_HEIGHT, 0x66000000);

        String time = LocalTime.now().format(TIME_FORMATTER);
        int tx = phoneLeft + PhoneTheme.PHONE_WIDTH - 6 - font.width(time);
        g.drawString(font, time, tx, phoneTop + 1, PhoneTheme.FONT_COLOR_STATUS, true);
        g.drawString(font, "●●●●", phoneLeft + 4, phoneTop + 1, 0xFFFFFFFF, true);

        // 非主界面显示返回箭头
        if (mode != Mode.MAIN) {
            String back = Component.translatable("mcphone.gui.back").getString();
            g.drawString(font, back + " 返回", phoneLeft + PhoneTheme.PHONE_WIDTH / 2 - 12,
                    phoneTop + 1, 0xFF88CCFF, true);
        }
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

            // 右箭头
            String arrow = ">";
            int ax = x + w - font.width(arrow) - 4;
            g.drawString(font, arrow, ax, y + 2, 0xFF888888, false);

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
    //  底部导航栏
    // ============================================================

    private void renderNavBar(GuiGraphics g) {
        int ny = phoneTop + PhoneTheme.PHONE_HEIGHT - PhoneTheme.NAV_BAR_HEIGHT;
        g.fill(phoneLeft, ny, phoneLeft + PhoneTheme.PHONE_WIDTH,
                phoneTop + PhoneTheme.PHONE_HEIGHT, PhoneTheme.COLOR_NAV_BAR);

        int cy = ny + PhoneTheme.NAV_BAR_HEIGHT / 2 - font.lineHeight / 2;
        int tw = PhoneTheme.PHONE_WIDTH / 3;
        String[] btns = {"◁", "○", "□"};
        for (int i = 0; i < btns.length; i++) {
            int bw = font.width(btns[i]);
            int bx = phoneLeft + tw * i + (tw - bw) / 2;
            g.drawString(font, btns[i], bx, cy, 0xFF888888, false);
        }
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
        };
    }

    // ============================================================
    //  键盘
    // ============================================================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            if (mode != Mode.MAIN) {
                navigateTo(Mode.MAIN);
                return true;
            }
            onClose();
            return true;
        }
        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            if (mode != Mode.MAIN) back();
            else onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public void onClose() { super.onClose(); }
    @Override public boolean isPauseScreen() { return false; }
}
