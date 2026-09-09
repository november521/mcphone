package com.november.mcphone.feature.terminal.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneChassis;
import com.november.mcphone.core.client.PhoneScreenOpener;
import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.feature.terminal.integration.Terminals;
import com.november.mcphone.feature.terminal.menu.TerminalSlotMenu;
import com.november.mcphone.feature.terminal.net.TerminalActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 终端卡槽的界面 —— 一个终端格 ＋ 玩家背包，外加一个「打开终端」按钮。
 *
 * 外壳不是自己画的
 *
 * 底、蒙版、格子底板、机身边框全部走 {@link PhoneChassis}，和末影箱、唱片仓那两个容器
 * 界面同一套：玩家的壁纸照样是背景，看着还是同一部手机。自己拿 {@code fill} 画一块深色
 * 面板也能用，但那样这一页就成了手机里唯一一块不认壁纸、不认外壳的地方。
 *
 * 关掉之后回手机，不是回世界
 *
 * 这一页是从手机上点进来的，退出去当然该回到手机——唱片仓也是这个行为。少了这一步，
 * 玩家取完终端就被丢回世界，还得再开一次机。
 *
 * 版面尺寸与坐标全部从 {@link TerminalSlotMenu} 读，这里一个数都不重写：格子的 x/y 是
 * 相对 leftPos/topPos 的，两边必须同一套基准。
 */
public class TerminalSlotScreen extends AbstractContainerScreen<TerminalSlotMenu> {

    private Button openButton;

    public TerminalSlotScreen(TerminalSlotMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = TerminalSlotMenu.IMAGE_WIDTH;
        this.imageHeight = TerminalSlotMenu.IMAGE_HEIGHT;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = TerminalSlotMenu.INVENTORY_LABEL_Y;
    }

    @Override
    protected void init() {
        super.init();

        openButton = Button.builder(
                        Component.translatable("mcphone.terminal.open"),
                        b -> MCphoneNetwork.sendToServer(
                                new TerminalActionPacket(TerminalActionPacket.Action.OPEN_TERMINAL)))
                .bounds(leftPos + TerminalSlotMenu.BUTTON_X, topPos + TerminalSlotMenu.BUTTON_Y,
                        TerminalSlotMenu.BUTTON_W, TerminalSlotMenu.BUTTON_H)
                .build();
        addRenderableWidget(openButton);
    }

    /**
     * 卡槽和背包里都没有终端时，按钮才点不动。
     *
     * 背包也算，是因为服务端本来就会退回背包里第一台（见 TerminalOpener）。点 App 不再
     * 自动开背包里那台之后，这个按钮就是那条路唯一的入口——身上明明带着终端却看到一个灰
     * 按钮，那才是真的没得用。
     *
     * 每帧问一次而不是在插拔时更新：格子内容是服务端同步过来的，客户端不知道它什么时候
     * 变；每帧读一次现成的值，代价可以忽略，也不会漏掉任何一次变化。
     */
    @Override
    public void containerTick() {
        super.containerTick();
        if (openButton != null) {
            openButton.active = !menu.getTerminal().isEmpty() || carriesTerminal();
        }
    }

    /**
     * 背包里有没有一台<b>开得了的</b>终端。
     *
     * 范围与服务端那一级对齐：Inventory 的全部槽位，含副手与盔甲位。认哪些牌子、以及
     * "认得出但远程开不了"（Tom's 的基础无线终端）都交给 {@link Terminals} 判断——这个类
     * 不认识任何一家存储模组，三家全没装时它也要能加载。
     */
    private boolean carriesTerminal() {
        if (minecraft == null || minecraft.player == null) return false;
        return Terminals.anyOpenableIn(minecraft.player.getInventory());
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        PhoneChassis.drawContainerBackdrop(g, leftPos, topPos, imageWidth, imageHeight, menu.slots);
    }

    /**
     * 三行字都走 {@link FontPalette}，跟着玩家在设置里选的字色走。
     *
     * 不用原版那个深灰（{@code 4210752}）：底是壁纸加一层压暗的蒙版，深灰在深壁纸上基本
     * 看不见。手机里其余每一页的字都是从这个调色板取的，这一页没有理由例外。
     */
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, titleLabelX, titleLabelY, FontPalette.title(), false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY,
                FontPalette.body(), false);

        // 卡槽旁边那行说明。空着和装着说的不是一回事
        Component hint = menu.getTerminal().isEmpty()
                ? Component.translatable("mcphone.terminal.hint_empty")
                : Component.translatable("mcphone.terminal.hint_installed");
        g.drawString(font, hint, TerminalSlotMenu.BUTTON_X, TerminalSlotMenu.HINT_Y,
                FontPalette.subtle(), false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);

        // 外壳画在格子与物品之后：它只覆盖机身外的那一圈，而贴图在内圈画的圆角要盖住
        // 背景才看得见。与末影箱、唱片仓同一条规矩
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
        PhoneScreenOpener.open(mc.player);
    }
}
