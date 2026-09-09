package com.november.mcphone.feature.music.client;

import com.november.mcphone.core.client.PhoneChassis;
import com.november.mcphone.core.client.PhoneScreen;
import com.november.mcphone.core.client.PhoneScreenOpener;
import com.november.mcphone.feature.music.menu.DiscBayMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 唱片仓的界面 —— 一个唱片格 ＋ 玩家背包，让玩家不必关手机就能把唱片拖进去。
 * 176×133 与原版漏斗一致（手机竖屏放不下 9 列格子）；关掉后回到音乐 App 而不是回世界。
 */
public class DiscBayScreen extends AbstractContainerScreen<DiscBayMenu> {

    public DiscBayScreen(DiscBayMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);

        // 格子坐标相对于 leftPos/topPos，尺寸必须与 Menu 用同一套基准
        this.imageWidth = DiscBayMenu.IMAGE_WIDTH;
        this.imageHeight = DiscBayMenu.IMAGE_HEIGHT;

        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = DiscBayMenu.INVENTORY_LABEL_Y;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        PhoneChassis.drawContainerBackdrop(g, leftPos, topPos, imageWidth, imageHeight, menu.slots);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 1.20.1 的 renderBackground 只收 GuiGraphics
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);

        // 外壳必须画在格子与物品之后：内圈圆角要盖住背景才看得见
        PhoneChassis.drawFrame(g, leftPos, topPos, imageWidth, imageHeight);

        // 物品提示必须最后画
        renderTooltip(g, mouseX, mouseY);
    }

    /** 顺序不能反：super 先通知服务端关菜单并把界面置空，之后才能开手机 */
    @Override
    public void onClose() {
        super.onClose();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (PhoneScreenOpener.open(mc.player) && mc.screen instanceof PhoneScreen phone) {
            phone.navigateTo(PhoneScreen.Mode.MUSIC_PLAYER);
        }
    }
}
