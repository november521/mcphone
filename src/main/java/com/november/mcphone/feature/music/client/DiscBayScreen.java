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
 * 唱片仓的界面 —— 一个唱片格 ＋ 玩家背包。
 *
 * ================================================================
 * 它补的是哪一个洞
 * ================================================================
 *
 * 手机主屏是普通 Screen，里面没有背包，而唱片仓原本只认主手。玩家想放一张
 * 唱片进去就得关手机 → 把唱片翻到手上 → 再开手机 → 点那一条。这个界面让
 * 他直接把唱片拖进去；主手那条路照旧留着，手上正好拿着时一步到位。
 *
 * ================================================================
 * 尺寸与外观
 * ================================================================
 *
 * 176×133，与原版漏斗一致 —— 手机竖屏机身只有 120px 宽，放不下 9 列格子
 * （需 162px）。为了迁就外壳而把背包拆成页签，会导致看不见对面也没法拖拽，
 * 而"把唱片放进去"正是这个界面唯一的用途。手机的视觉由外壳边框与壁纸保留，
 * 与末影箱那个界面同一条取舍。
 *
 * ================================================================
 * 关掉之后回到音乐 App，而不是回到游戏
 * ================================================================
 *
 * 玩家是从音乐 App 点进来的，放完唱片十有八九要按播放。原版容器界面关掉
 * 就回世界，那样他还得再开一次机、再点一次音乐 —— 而这个界面存在的理由
 * 本来就是省掉那几步。
 *
 * 手机不在身上了（掉了、被别人拿了）就什么都不做：那时 PhoneLocation.find
 * 找不到东西，回不去也是对的。
 */
public class DiscBayScreen extends AbstractContainerScreen<DiscBayMenu> {

    public DiscBayScreen(DiscBayMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);

        // 格子坐标正是相对于 leftPos/topPos，两边必须用同一套基准，
        // 否则格子会画在背板外面
        this.imageWidth = DiscBayMenu.IMAGE_WIDTH;
        this.imageHeight = DiscBayMenu.IMAGE_HEIGHT;

        // 标题与"物品栏"两行字的位置，同原版
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
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
        // 光标下的物品提示由父类的 renderTooltip 负责，必须在最后画
        renderTooltip(g, mouseX, mouseY);
    }

    /**
     * 关掉就回到音乐 App，理由见类注释。
     *
     * 顺序不能反：super 那一句会通知服务端关菜单并把界面置空，之后才轮到
     * 我们开手机。反过来的话手机会被立刻顶掉。
     */
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
