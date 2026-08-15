package com.november.mcphone.core.client;

import com.november.mcphone.core.menu.PhoneContainerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * 手机里的容器界面 —— 容器内容与玩家背包同屏显示。
 *
 * 继承原版 AbstractContainerScreen 而不是自己撸：点击、拖拽分配、
 * shift 搬运、双击整理、悬停提示、光标上跟随的物品，全部由它处理，
 * 本类只负责画背景。
 *
 * 尺寸沿用原版箱子的 176×168 而不是手机竖屏机身：机身只有 120px 宽，
 * 放不下 9 列格子（需 162px）。为了迁就外壳而把容器与背包拆成两页，
 * 会导致看不见对面、也没法拖拽——而"把东西放进末影箱"正是这个界面
 * 唯一的用途。手机的视觉由外壳边框与壁纸保留。
 */
public class PhoneContainerScreen extends AbstractContainerScreen<PhoneContainerMenu> {

    /** 格子底板颜色（半透明黑，压在壁纸上仍看得清物品） */
    private static final int COLOR_SLOT_BG = 0x88000000;

    /** 压在壁纸上的暗色蒙版，保证白色标题与物品轮廓可读 */
    private static final int COLOR_SCRIM = 0x66000000;

    public PhoneContainerScreen(PhoneContainerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);

        // 尺寸与原版箱子一致，格子坐标正是相对于 leftPos/topPos，
        // 两边必须用同一套基准，否则格子会画在背板外面
        this.imageWidth = PhoneContainerMenu.IMAGE_WIDTH;
        this.imageHeight = menu.getImageHeight();

        // 标题与"物品栏"两行字的位置，同原版
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = menu.getInventoryLabelY();
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        // 外壳与壁纸按本界面的尺寸绘制，视觉上仍是同一部手机
        PhoneChassis.drawFrameAndWallpaper(g, leftPos, topPos, imageWidth, imageHeight);

        // 壁纸可能很亮，压一层暗色蒙版保证文字与物品看得清
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, COLOR_SCRIM);

        renderSlotBackplates(g);
    }

    /** 画格子底板，让格子在任意壁纸下都分得清边界 */
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

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
        // 光标下的物品提示由父类的 renderTooltip 负责，必须在最后画
        renderTooltip(g, mouseX, mouseY);
    }
}
