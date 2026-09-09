package com.november.mcphone.feature.terminal.integration.toms;

import com.november.mcphone.feature.terminal.integration.TerminalIntegration;
import com.november.mcphone.feature.terminal.integration.TerminalSource;
import com.tom.storagemod.item.WirelessTerminal;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Tom's Simple Storage 的接入 —— 高级无线终端。
 *
 * 这一家不需要"位置"
 *
 * AE2 与 RS 都要我们回答"那个物品去哪儿找"，因为它们要往那个 ItemStack 上写东西（耗电、
 * 界面设置），而且客户端重建菜单时要再找一次。Tom's 的
 * {@code open(Player, ItemStack)} 直接吃 ItemStack，开出来的是<b>终端方块自己的菜单</b>
 * ——它把绑定的维度与坐标存在物品的 DataComponent 里，打开时等价于"隔空对着那个方块右键"。
 * 所以 {@link TerminalSource} 在这一家用不上，装在手机卡槽里也照样开得了。
 *
 * 基础无线终端开不了，这不是 bug
 *
 * Tom's 有两把终端，它们都实现 {@link WirelessTerminal}：
 *
 *   无线终端（基础）    {@code open} 的方法体是<b>一句 return</b>，{@code canOpen} 恒为 false
 *   高级无线终端        {@code open} 走 {@code activateTerminal}，{@code canOpen} 恒为 true
 *
 * 基础那把的设计就是"瞄准范围内的终端方块右键"（它的 {@code use} 是一次 {@code player.pick}
 * 射线），根本没有"隔空打开"这回事。所以从手机上点它，字节码层面就是什么都不会发生，
 * <b>不崩、不报错、没有任何反馈</b>——这正是 {@link #canOpen} 存在的理由：把它挡在卡槽外面，
 * 并且在背包里遇到它时告诉玩家为什么跳过了它。
 *
 * 用 {@code canOpen} 而不是 {@code instanceof AdvWirelessTerminalItem}：前者是 Tom's 自己
 * 给出的答案，它哪天让基础终端也支持远程，我们跟着变；后者是我们替它下的判断，会过时。
 */
public final class TomsStorageIntegration implements TerminalIntegration {

    public static final String MODID = "toms_storage";

    /** 显示名。与 modid 一样是编译期常量，理由见 {@code Ae2Integration.NAME} */
    public static final String NAME = "Tom's Simple Storage Mod";

    @Override
    public String modId() {
        return MODID;
    }

    @Override
    public String displayName() {
        return NAME;
    }

    @Override
    public boolean claims(ItemStack stack) {
        return stack.getItem() instanceof WirelessTerminal;
    }

    @Override
    public boolean canOpen(ItemStack stack) {
        return stack.getItem() instanceof WirelessTerminal terminal && terminal.canOpen(stack);
    }

    /**
     * 绑没绑定、绑的那个方块还在不在、跨不跨维度，全由 Tom's 在 {@code open} 里自己查，
     * 查不过它自己给玩家发消息（"终端不可达"之类）。
     */
    @Override
    public boolean open(ServerPlayer player, ItemStack stack, TerminalSource source) {
        if (!(stack.getItem() instanceof WirelessTerminal terminal)) return false;
        if (!terminal.canOpen(stack)) return false;

        terminal.open(player, stack);
        return true;
    }
}
