package com.november.mcphone.gui;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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
    public enum Mode { MAIN, SETTINGS, WALLPAPER_PICKER }

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
        this.mode = target;
        this.hoveredSettingIdx = -1;
    }

    public void back() {
        mode = switch (mode) {
            case SETTINGS, WALLPAPER_PICKER -> Mode.MAIN;
            default -> Mode.MAIN;
        };
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
            case MAIN            -> renderAppGrid(g);
            case SETTINGS        -> renderSettingsList(g, mouseX, mouseY);
            case WALLPAPER_PICKER -> renderWallpaperPicker(g, mouseX, mouseY);
        }

        renderNavBar(g);
        g.pose().popPose();

        if (mode == Mode.MAIN)       updateAppHover(mouseX, mouseY);
        if (mode == Mode.SETTINGS)   updateSettingsHover(mouseX, mouseY);
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

            g.blit(wp.texture(),
                    phoneLeft, phoneTop,              // 屏幕目标位置
                    srcX, srcY,                       // 纹理源 UV
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,  // 目标宽高
                    srcW, srcH,                       // 源区域宽高
                    texW, texH);                      // 纹理总宽高
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
            g.drawString(font, "◀", phoneLeft + PhoneTheme.PHONE_WIDTH / 2 - 3,
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
    //  壁纸选择器
    // ============================================================

    private void renderWallpaperPicker(GuiGraphics g, int mx, int my) {
        wallpaperPicker.render(g, phoneLeft, phoneTop,
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
                // 点击手机屏幕外区域 → 关闭手机
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
                    // 选完壁纸返回设置列表
                    navigateTo(Mode.SETTINGS);
                }
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
