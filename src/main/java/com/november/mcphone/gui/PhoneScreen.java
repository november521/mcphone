package com.november.mcphone.gui;

import com.november.mcphone.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 手机主屏幕 GUI。
 *
 * 玩家右键手机物品时打开此界面。
 * 界面渲染一个仿手机外观的屏幕，展示已注册的 App 图标网格。
 *
 * 支持两个模式：
 * - MAIN: 主屏幕 App 网格
 * - WALLPAPER_PICKER: 壁纸选择界面
 */
public final class PhoneScreen extends Screen {

    private enum Mode { MAIN, WALLPAPER_PICKER }

    // ---- 时间格式化器（static final，复用） ----
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm");

    // ---- 打开动画 ----
    private final long openTimeMs;
    private boolean animationDone;

    // ---- 当前模式 ----
    private Mode mode = Mode.MAIN;
    private final WallpaperPicker wallpaperPicker = new WallpaperPicker();

    // ---- 鼠标交互 ----
    private int hoveredAppIndex = -1;

    // ---- 布局计算缓存 ----
    private int phoneLeft;
    private int phoneTop;
    private int gridStartX;
    private int gridStartY;
    private boolean layoutDirty = true;

    private long nowMs;

    public PhoneScreen() {
        super(Component.translatable("mcphone.gui.home"));
        this.openTimeMs = System.currentTimeMillis();
        this.animationDone = PhoneTheme.OPEN_ANIMATION_MS <= 0;
    }

    /** 切换到壁纸选择模式 */
    public void openWallpaperPicker() {
        this.mode = Mode.WALLPAPER_PICKER;
    }

    /** 返回主屏幕 */
    public void backToMain() {
        this.mode = Mode.MAIN;
    }

    // ============================================================
    //  布局
    // ============================================================

    private void computeLayout() {
        if (!layoutDirty) return;

        final int screenW = this.width;
        final int screenH = this.height;
        final int phoneW = PhoneTheme.PHONE_TOTAL_WIDTH;
        final int phoneH = PhoneTheme.PHONE_TOTAL_HEIGHT;

        this.phoneLeft = (screenW - phoneW) / 2 + PhoneTheme.PHONE_BORDER;
        this.phoneTop = (screenH - phoneH) / 2 + PhoneTheme.PHONE_BORDER + PhoneTheme.SCREEN_Y_OFFSET;

        this.gridStartX = this.phoneLeft + PhoneTheme.APP_GRID_PADDING_LEFT;
        this.gridStartY = this.phoneTop + PhoneTheme.STATUS_BAR_HEIGHT + PhoneTheme.APP_GRID_PADDING_TOP;

        this.layoutDirty = false;
    }

    private void invalidateLayout() {
        this.layoutDirty = true;
    }

    @Override
    protected void init() {
        super.init();
        invalidateLayout();
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        invalidateLayout();
    }

    // ============================================================
    //  每帧渲染
    // ============================================================

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.nowMs = System.currentTimeMillis();

        computeLayout();

        // 背景遮罩
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        float scale = getAnimationScale();

        // 缩放动画
        int phoneCenterX = this.phoneLeft + PhoneTheme.PHONE_WIDTH / 2;
        int phoneCenterY = this.phoneTop + PhoneTheme.PHONE_HEIGHT / 2;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(phoneCenterX, phoneCenterY, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);
        guiGraphics.pose().translate(-phoneCenterX, -phoneCenterY, 0);

        renderPhoneFrame(guiGraphics);
        renderScreenContent(guiGraphics, mouseX, mouseY, partialTick);

        if (mode == Mode.WALLPAPER_PICKER) {
            renderWallpaperPicker(guiGraphics, mouseX, mouseY);
        } else {
            renderAppGrid(guiGraphics, mouseX, mouseY, partialTick);
        }

        renderNavBar(guiGraphics);

        guiGraphics.pose().popPose();

        if (mode == Mode.MAIN) {
            updateHover(mouseX, mouseY);
        }
    }

    // ============================================================
    //  手机外壳
    // ============================================================

    private void renderPhoneFrame(GuiGraphics g) {
        final int frameLeft = this.phoneLeft - PhoneTheme.PHONE_BORDER;
        final int frameTop = this.phoneTop - PhoneTheme.PHONE_BORDER;
        final int frameW = PhoneTheme.PHONE_TOTAL_WIDTH;
        final int frameH = PhoneTheme.PHONE_TOTAL_HEIGHT;

        g.fill(frameLeft, frameTop, frameLeft + frameW, frameTop + frameH,
                PhoneTheme.COLOR_FRAME);
        g.fill(frameLeft, frameTop, frameLeft + frameW, frameTop + 2,
                PhoneTheme.COLOR_FRAME_HIGHLIGHT);

        // ---- 壁纸或纯色背景 ----
        String wallpaperName = NetworkHandler.WakeholderData.get();
        ResourceLocation wallpaperTex = WallpaperStore.findTexture(wallpaperName);

        if (wallpaperTex != null) {
            // 用壁纸纹理填充屏幕
            g.blit(wallpaperTex,
                    this.phoneLeft, this.phoneTop,
                    0, 0,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT);
        } else {
            // 纯色背景
            g.fill(this.phoneLeft, this.phoneTop,
                    this.phoneLeft + PhoneTheme.PHONE_WIDTH,
                    this.phoneTop + PhoneTheme.PHONE_HEIGHT,
                    PhoneTheme.COLOR_SCREEN_BG);
        }
    }

    // ============================================================
    //  状态栏
    // ============================================================

    private void renderScreenContent(GuiGraphics g, int mouseX, int mouseY, float pt) {
        // 状态栏半透明背景
        g.fill(this.phoneLeft, this.phoneTop,
                this.phoneLeft + PhoneTheme.PHONE_WIDTH,
                this.phoneTop + PhoneTheme.STATUS_BAR_HEIGHT,
                0x66000000);

        String timeStr = LocalTime.now().format(TIME_FORMATTER);
        int timeX = this.phoneLeft + PhoneTheme.PHONE_WIDTH - 6
                - this.font.width(timeStr);
        g.drawString(this.font, timeStr, timeX, this.phoneTop + 1,
                PhoneTheme.FONT_COLOR_STATUS, true);

        g.drawString(this.font, "●●●●", this.phoneLeft + 4, this.phoneTop + 1,
                0xFFFFFFFF, true);

        // 壁纸模式下显示返回按钮
        if (mode == Mode.WALLPAPER_PICKER) {
            int backX = this.phoneLeft + 4;
            int backY = this.phoneTop + 4 + PhoneTheme.STATUS_BAR_HEIGHT;
            g.drawString(this.font, "◀ 返回",
                    backX, backY + PhoneTheme.STATUS_BAR_HEIGHT, 0xFF88CCFF, true);
        }
    }

    // ============================================================
    //  App 网格
    // ============================================================

    private void renderAppGrid(GuiGraphics g, int mouseX, int mouseY, float pt) {
        final var apps = PhoneScreenRegistry.getApps();
        final int iconSize = PhoneTheme.APP_ICON_SIZE;
        final int spaceX = PhoneTheme.APP_GRID_SPACING_X;
        final int cols = PhoneTheme.APP_COLUMNS;

        final int cellW = iconSize + spaceX;
        final int cellH = iconSize + (int)(this.font.lineHeight * PhoneTheme.APP_NAME_SCALE) + 4;

        for (int i = 0; i < apps.size(); i++) {
            int col = i % cols;
            int row = i / cols;

            int iconX = this.gridStartX + col * cellW;
            int iconY = this.gridStartY + row * cellH;

            if (iconY + iconSize > this.phoneTop + PhoneTheme.PHONE_HEIGHT - PhoneTheme.NAV_BAR_HEIGHT) {
                break;
            }

            // 按下/悬停高亮
            if (i == this.hoveredAppIndex) {
                g.fill(iconX - 2, iconY - 2, iconX + iconSize + 2, iconY + iconSize + 2,
                        PhoneTheme.COLOR_APP_PRESSED);
            }

            AppEntry app = apps.get(i);
            app.renderIcon(g, iconX, iconY, iconSize, pt);

            // App 名称
            int nameW = this.font.width(app.getName());
            float nameScale = PhoneTheme.APP_NAME_SCALE;
            int nameX = iconX + (iconSize - (int)(nameW * nameScale)) / 2;
            int nameY = iconY + iconSize + 2;
            g.pose().pushPose();
            g.pose().translate(nameX + (int)(nameW * nameScale) / 2f, nameY, 0);
            g.pose().scale(nameScale, nameScale, 1.0f);
            g.pose().translate(-(nameX + (int)(nameW * nameScale) / 2f), -nameY, 0);
            g.drawString(this.font, app.getName(),
                    nameX, nameY, PhoneTheme.FONT_COLOR_APP_NAME, false);
            g.pose().popPose();
        }
    }

    // ============================================================
    //  壁纸选择器
    // ============================================================

    private void renderWallpaperPicker(GuiGraphics g, int mouseX, int mouseY) {
        wallpaperPicker.render(g,
                this.phoneLeft, this.phoneTop,
                PhoneTheme.PHONE_WIDTH, PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.STATUS_BAR_HEIGHT, PhoneTheme.NAV_BAR_HEIGHT,
                mouseX, mouseY, this.font);
    }

    // ============================================================
    //  底部导航栏
    // ============================================================

    private void renderNavBar(GuiGraphics g) {
        int navY = this.phoneTop + PhoneTheme.PHONE_HEIGHT - PhoneTheme.NAV_BAR_HEIGHT;
        g.fill(this.phoneLeft, navY,
                this.phoneLeft + PhoneTheme.PHONE_WIDTH,
                this.phoneTop + PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.COLOR_NAV_BAR);

        int navCenterY = navY + PhoneTheme.NAV_BAR_HEIGHT / 2 - this.font.lineHeight / 2;
        int thirdW = PhoneTheme.PHONE_WIDTH / 3;
        String[] btns = {"◁", "○", "□"};
        for (int i = 0; i < btns.length; i++) {
            int bw = this.font.width(btns[i]);
            int bx = this.phoneLeft + thirdW * i + (thirdW - bw) / 2;
            g.drawString(this.font, btns[i], bx, navCenterY, 0xFF888888, false);
        }
    }

    // ============================================================
    //  动画
    // ============================================================

    private float getAnimationScale() {
        if (animationDone) return 1.0f;

        long elapsed = nowMs - openTimeMs;
        int duration = PhoneTheme.OPEN_ANIMATION_MS;

        if (elapsed >= duration) {
            animationDone = true;
            return 1.0f;
        }

        float t = (float) elapsed / duration;
        float c1 = 1.70158f;
        float c3 = c1 + 1;
        return (float)(1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2)) * 0.4f + 0.6f;
    }

    // ============================================================
    //  鼠标
    // ============================================================

    private void updateHover(int mouseX, int mouseY) {
        float scale = getAnimationScale();
        int phoneCenterX = this.phoneLeft + PhoneTheme.PHONE_WIDTH / 2;
        int phoneCenterY = this.phoneTop + PhoneTheme.PHONE_HEIGHT / 2;

        int localX = (int)((mouseX - phoneCenterX) / scale + phoneCenterX);
        int localY = (int)((mouseY - phoneCenterY) / scale + phoneCenterY);

        final var apps = PhoneScreenRegistry.getApps();
        final int iconSize = PhoneTheme.APP_ICON_SIZE;
        final int cols = PhoneTheme.APP_COLUMNS;
        final int cellW = iconSize + PhoneTheme.APP_GRID_SPACING_X;
        final int cellH = iconSize + (int)(this.font.lineHeight * PhoneTheme.APP_NAME_SCALE) + 4;

        this.hoveredAppIndex = -1;

        for (int i = 0; i < apps.size(); i++) {
            int col = i % cols;
            int row = i / cols;

            int iconX = this.gridStartX + col * cellW;
            int iconY = this.gridStartY + row * cellH;

            if (localX >= iconX && localX <= iconX + iconSize
                    && localY >= iconY && localY <= iconY + iconSize + 6) {
                this.hoveredAppIndex = i;
                return;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 壁纸选择模式下转发给 picker
        if (mode == Mode.WALLPAPER_PICKER) {
            if (wallpaperPicker.mouseClicked(mouseX, mouseY, button)) {
                backToMain();
                return true;
            }
            return true;
        }

        if (button == 0 && this.hoveredAppIndex >= 0) {
            AppEntry app = PhoneScreenRegistry.getApp(this.hoveredAppIndex);
            if (app != null) {
                app.onPress();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ============================================================
    //  键盘
    // ============================================================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 壁纸模式下 ESC 返回主屏幕
        if (mode == Mode.WALLPAPER_PICKER && keyCode == 256) { // 256 = ESC
            backToMain();
            return true;
        }

        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            if (mode == Mode.WALLPAPER_PICKER) {
                backToMain();
            } else {
                this.onClose();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
