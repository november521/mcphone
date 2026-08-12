package com.november.mcphone.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 手机主屏幕 GUI。
 *
 * 玩家右键手机物品时打开此界面。
 * 界面渲染一个仿手机外观的屏幕，展示已注册的 App 图标网格。
 *
 * 【如何自定义界面样式】
 * 所有视觉参数集中在 {@link PhoneTheme} 中：
 * - 改颜色 → 修改 COLOR_* 常量
 * - 改大小 → 修改 PHONE_WIDTH / PHONE_HEIGHT / APP_ICON_SIZE 等
 * - 换贴图 → 在 assets/mcphone/textures/gui/ 下放置对应 PNG
 *
 * 【如何添加新 App】
 * 参考 {@link PhoneScreenRegistry#registerDefaultApps} 中的示例，
 * 调用 PhoneScreenRegistry.register(...) 即可。
 *
 * 【性能说明】
 * - App 列表只读，没有每帧 new 对象
 * - 鼠标悬停检测仅遍历可见 App（O(n), n≤16）
 * - 计时器用 System.currentTimeMillis()，避免每帧创建新对象
 */
public final class PhoneScreen extends Screen {

    // ---- 时间格式化器（static final，复用） ----
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm");

    // ---- 打开动画 ----
    private final long openTimeMs;          // GUI 打开时刻
    private boolean animationDone;

    // ---- 鼠标交互 ----
    /** 当前鼠标悬停在哪个 App 索引上，-1 表示无 */
    private int hoveredAppIndex = -1;

    // ---- 布局计算缓存（窗口大小改变时重新计算） ----
    /** 手机屏幕区域左上角 X（内容区，不含边框） */
    private int phoneLeft;
    /** 手机屏幕区域左上角 Y（内容区，不含边框） */
    private int phoneTop;
    /** App 网格起始 X */
    private int gridStartX;
    /** App 网格起始 Y */
    private int gridStartY;
    private boolean layoutDirty = true;

    /** 当前帧时间（缓存在 render 开头，避免多次调用 System.currentTimeMillis） */
    private long nowMs;

    public PhoneScreen() {
        super(Component.translatable("mcphone.gui.home"));
        this.openTimeMs = System.currentTimeMillis();
        this.animationDone = PhoneTheme.OPEN_ANIMATION_MS <= 0;
    }

    // ============================================================
    //  布局计算
    // ============================================================

    /**
     * 一次性计算所有布局坐标，结果缓存到下次 invalidate 为止。
     * 只在窗口大小改变时重新计算。
     */
    private void computeLayout() {
        if (!layoutDirty) return;

        final int screenW = this.width;
        final int screenH = this.height;

        // 手机总尺寸（含边框）
        final int phoneW = PhoneTheme.PHONE_TOTAL_WIDTH;
        final int phoneH = PhoneTheme.PHONE_TOTAL_HEIGHT;

        // 居中放置
        this.phoneLeft = (screenW - phoneW) / 2 + PhoneTheme.PHONE_BORDER;
        this.phoneTop = (screenH - phoneH) / 2 + PhoneTheme.PHONE_BORDER + PhoneTheme.SCREEN_Y_OFFSET;

        // App 网格起始坐标
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

        // 预计算布局
        computeLayout();

        // --- 背景遮罩 ---
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        // --- 获取动画缩放系数 ---
        float scale = getAnimationScale();

        // --- 渲染手机 ---
        guiGraphics.pose().pushPose();

        // 以手机屏幕中心为原点做缩放动画
        int phoneCenterX = this.phoneLeft + PhoneTheme.PHONE_WIDTH / 2;
        int phoneCenterY = this.phoneTop + PhoneTheme.PHONE_HEIGHT / 2;

        guiGraphics.pose().translate(phoneCenterX, phoneCenterY, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);
        guiGraphics.pose().translate(-phoneCenterX, -phoneCenterY, 0);

        // 绘制手机边框
        renderPhoneFrame(guiGraphics);
        // 绘制屏幕内容
        renderScreenContent(guiGraphics, mouseX, mouseY, partialTick);
        // 绘制 App 网格
        renderAppGrid(guiGraphics, mouseX, mouseY, partialTick);
        // 绘制底部导航栏
        renderNavBar(guiGraphics);

        guiGraphics.pose().popPose();

        // --- 更新 hover 状态 ---
        updateHover(mouseX, mouseY);
    }

    // ============================================================
    //  手机外壳
    // ============================================================

    private void renderPhoneFrame(GuiGraphics g) {
        final int frameLeft = this.phoneLeft - PhoneTheme.PHONE_BORDER;
        final int frameTop = this.phoneTop - PhoneTheme.PHONE_BORDER;
        final int frameW = PhoneTheme.PHONE_TOTAL_WIDTH;
        final int frameH = PhoneTheme.PHONE_TOTAL_HEIGHT;

        // 外壳主体
        g.fill(frameLeft, frameTop, frameLeft + frameW, frameTop + frameH,
                PhoneTheme.COLOR_FRAME);

        // 顶部高光条
        g.fill(frameLeft, frameTop, frameLeft + frameW, frameTop + 2,
                PhoneTheme.COLOR_FRAME_HIGHLIGHT);

        // 屏幕区域（纯色背景，贴图可选）
        g.fill(this.phoneLeft, this.phoneTop,
                this.phoneLeft + PhoneTheme.PHONE_WIDTH,
                this.phoneTop + PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.COLOR_SCREEN_BG);
    }

    // ============================================================
    //  屏幕内容（状态栏）
    // ============================================================

    private void renderScreenContent(GuiGraphics g, int mouseX, int mouseY, float pt) {
        // 状态栏背景
        g.fill(this.phoneLeft, this.phoneTop,
                this.phoneLeft + PhoneTheme.PHONE_WIDTH,
                this.phoneTop + PhoneTheme.STATUS_BAR_HEIGHT,
                PhoneTheme.COLOR_STATUS_BAR);

        // 状态栏时间（居中偏右）
        String timeStr = LocalTime.now().format(TIME_FORMATTER);
        int timeX = this.phoneLeft + PhoneTheme.PHONE_WIDTH - 6
                - this.font.width(timeStr);
        int timeY = this.phoneTop + 4;
        g.drawString(this.font, timeStr, timeX, timeY, PhoneTheme.FONT_COLOR_STATUS, true);

        // 左侧信号/电池占位符
        g.drawString(this.font, "●●●●", this.phoneLeft + 4, this.phoneTop + 4,
                0xFFFFFFFF, true);
    }

    // ============================================================
    //  App 图标网格
    // ============================================================

    private void renderAppGrid(GuiGraphics g, int mouseX, int mouseY, float pt) {
        final var apps = PhoneScreenRegistry.getApps();
        final int iconSize = PhoneTheme.APP_ICON_SIZE;
        final int spaceX = PhoneTheme.APP_GRID_SPACING_X;
        final int spaceY = PhoneTheme.APP_GRID_SPACING_Y;
        final int cols = PhoneTheme.APP_COLUMNS;

        final int cellW = iconSize + spaceX;
        final int cellH = iconSize + (int)(this.font.lineHeight * PhoneTheme.APP_NAME_SCALE) + 4;

        for (int i = 0; i < apps.size(); i++) {
            int col = i % cols;
            int row = i / cols;

            int iconX = this.gridStartX + col * cellW;
            int iconY = this.gridStartY + row * cellH;

            // 如果图标区域超出屏幕，停止绘制
            if (iconY + iconSize > this.phoneTop + PhoneTheme.PHONE_HEIGHT - PhoneTheme.NAV_BAR_HEIGHT) {
                break;
            }

            // 绘制图标背景（按下/悬停高亮）
            if (i == this.hoveredAppIndex) {
                g.fill(iconX - 2, iconY - 2, iconX + iconSize + 2, iconY + iconSize + 2,
                        PhoneTheme.COLOR_APP_PRESSED);
            }

            // 绘制 App 图标
            AppEntry app = apps.get(i);
            app.renderIcon(g, iconX, iconY, iconSize, pt);

            // 绘制 App 名称
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
    //  底部导航栏
    // ============================================================

    private void renderNavBar(GuiGraphics g) {
        int navY = this.phoneTop + PhoneTheme.PHONE_HEIGHT - PhoneTheme.NAV_BAR_HEIGHT;
        g.fill(this.phoneLeft, navY,
                this.phoneLeft + PhoneTheme.PHONE_WIDTH,
                this.phoneTop + PhoneTheme.PHONE_HEIGHT,
                PhoneTheme.COLOR_NAV_BAR);

        // 三个导航按钮占位 (◁  ○  □)
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

    /** 返回 0.0~1.0 的缩放系数 */
    private float getAnimationScale() {
        if (animationDone) return 1.0f;

        long elapsed = nowMs - openTimeMs;
        int duration = PhoneTheme.OPEN_ANIMATION_MS;

        if (elapsed >= duration) {
            animationDone = true;
            return 1.0f;
        }

        // easeOutBack 缓动：从 0.6 缩放到 1.0
        float t = (float) elapsed / duration;
        float c1 = 1.70158f;
        float c3 = c1 + 1;
        return (float)(1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2)) * 0.4f + 0.6f;
    }

    // ============================================================
    //  鼠标事件
    // ============================================================

    /**
     * 根据鼠标坐标判断当前悬停的 App。
     * 注意：这里需要反算缩放后的坐标。
     */
    private void updateHover(int mouseX, int mouseY) {
        // 反算缩放
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
        if (button == 0 && this.hoveredAppIndex >= 0) { // 左键
            AppEntry app = PhoneScreenRegistry.getApp(this.hoveredAppIndex);
            if (app != null) {
                app.onPress();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ============================================================
    //  键盘事件
    // ============================================================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // E 键（打开背包的键）也关闭手机，方便玩家快速切回
        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        // ESC 关闭由父类 Screen 自动处理，无需额外覆盖
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false; // 不暂停游戏（手机打开时游戏继续运行）
    }
}
