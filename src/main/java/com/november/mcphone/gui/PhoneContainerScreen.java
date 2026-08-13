package com.november.mcphone.gui;

import com.november.mcphone.menu.PhoneContainerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * 手机里的容器界面 —— 在机身内画格子。
 *
 * 继承原版 AbstractContainerScreen 而不是自己撸：点击、拖拽分配、
 * shift 搬运、悬停提示、光标上跟随的物品，全部由它处理，我们只负责
 * 画背景与页签。
 *
 * 页签切换只改 {@link PhoneContainerMenu#setActiveTab}，格子的显隐由
 * Slot.isActive() 决定，原版会自动跳过未激活的格子（不绘制、点不到）。
 */
public class PhoneContainerScreen extends AbstractContainerScreen<PhoneContainerMenu> {

    /** 页签栏高度，位于状态栏与格子区之间 */
    private static final int TAB_BAR_HEIGHT = 14;

    /** 格子底板颜色（半透明黑，压在壁纸上仍看得清物品） */
    private static final int COLOR_SLOT_BG = 0x88000000;

    /** 当前页签的高亮底色 */
    private static final int COLOR_TAB_ACTIVE = 0xFF0F3460;

    /** 非当前页签的底色 */
    private static final int COLOR_TAB_IDLE = 0x66000000;

    private final Component containerTabName;

    public PhoneContainerScreen(PhoneContainerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.containerTabName = title;

        // 界面尺寸＝手机整机尺寸。leftPos/topPos 由父类按此居中算出，
        // 而菜单里的格子坐标正是相对于它们，两边必须用同一套基准。
        this.imageWidth = PhoneTheme.PHONE_TOTAL_WIDTH;
        this.imageHeight = PhoneTheme.PHONE_TOTAL_HEIGHT;

        // 关掉父类默认的两行标题（"箱子" / "物品栏"）：手机屏幕只有 120px 宽，
        // 画不下，而且我们用页签表达同样的信息。负坐标会画到机身外，
        // 故改为在 renderLabels 里完全不画。
        this.titleLabelX = 0;
        this.titleLabelY = 0;
        this.inventoryLabelX = 0;
        this.inventoryLabelY = 0;
    }

    /** 屏幕内区域左上角 X（不含边框），与 PhoneScreen 的 phoneLeft 同义 */
    private int phoneLeft() {
        return leftPos + PhoneTheme.PHONE_BORDER;
    }

    /** 屏幕内区域左上角 Y（不含边框） */
    private int phoneTop() {
        return topPos + PhoneTheme.PHONE_BORDER;
    }

    /** 页签栏顶端 Y —— 紧贴状态栏下方 */
    private int tabBarTop() {
        return phoneTop() + PhoneTheme.STATUS_BAR_HEIGHT;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        PhoneChassis.drawFrameAndWallpaper(g, phoneLeft(), phoneTop());
        PhoneChassis.drawStatusBar(g, font, phoneLeft(), phoneTop(), null);
        renderTabBar(g);
        renderSlotBackplates(g);
        PhoneChassis.drawNavBar(g, font, phoneLeft(), phoneTop());
    }

    /** 两个页签平分屏幕宽度 */
    private void renderTabBar(GuiGraphics g) {
        int y = tabBarTop();
        int halfW = PhoneTheme.PHONE_WIDTH / 2;
        int activeTab = menu.getActiveTab();

        drawTab(g, phoneLeft(), y, halfW, containerTabName.getString(),
                activeTab == PhoneContainerMenu.TAB_CONTENT);
        drawTab(g, phoneLeft() + halfW, y, PhoneTheme.PHONE_WIDTH - halfW,
                Component.translatable("mcphone.container.tab_inventory").getString(),
                activeTab == PhoneContainerMenu.TAB_INVENTORY);
    }

    private void drawTab(GuiGraphics g, int x, int y, int w, String label, boolean active) {
        g.fill(x, y, x + w, y + TAB_BAR_HEIGHT, active ? COLOR_TAB_ACTIVE : COLOR_TAB_IDLE);
        if (active) {
            // 底部一条高亮，明确"当前在这一页"
            g.fill(x, y + TAB_BAR_HEIGHT - 1, x + w, y + TAB_BAR_HEIGHT, 0xFF88CCFF);
        }
        int tw = font.width(label);
        int tx = x + (w - tw) / 2;
        int ty = y + (TAB_BAR_HEIGHT - font.lineHeight) / 2;
        g.drawString(font, label, tx, ty, active ? 0xFFFFFFFF : 0xFF999999, false);
    }

    /**
     * 画格子底板。
     *
     * 只画当前页签的格子——判据与原版渲染格子用的是同一个 isActive()，
     * 否则底板会留在没有格子的地方。
     */
    private void renderSlotBackplates(GuiGraphics g) {
        for (Slot slot : menu.slots) {
            if (!slot.isActive()) continue;
            // 格子的 x/y 是物品左上角，18×18 的框要往外扩 1px
            int sx = leftPos + slot.x - 1;
            int sy = topPos + slot.y - 1;
            g.fill(sx, sy, sx + PhoneContainerMenu.SLOT_SIZE, sy + PhoneContainerMenu.SLOT_SIZE,
                    COLOR_SLOT_BG);
        }
    }

    /**
     * 不画父类默认的标题与"物品栏"两行字：手机屏幕太窄放不下，
     * 页签已经表达了同样的信息。
     */
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 有意留空
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
        // 光标下的物品提示由父类的 renderTooltip 负责，必须在最后画
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 页签要抢在格子判定之前：页签栏与格子区不重叠，但父类会把
        // 落空的点击当作"点在容器外"处理，可能丢掉光标上的物品
        if (button == 0 && hitTestTabBar(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 命中页签栏则切换页签并返回 true */
    private boolean hitTestTabBar(double mouseX, double mouseY) {
        int y = tabBarTop();
        if (mouseY < y || mouseY >= y + TAB_BAR_HEIGHT) return false;
        if (mouseX < phoneLeft() || mouseX >= phoneLeft() + PhoneTheme.PHONE_WIDTH) return false;

        int halfW = PhoneTheme.PHONE_WIDTH / 2;
        boolean leftHalf = mouseX < phoneLeft() + halfW;
        menu.setActiveTab(leftHalf ? PhoneContainerMenu.TAB_CONTENT : PhoneContainerMenu.TAB_INVENTORY);
        return true;
    }
}
