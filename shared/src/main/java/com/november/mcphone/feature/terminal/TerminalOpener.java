package com.november.mcphone.feature.terminal;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.terminal.integration.TerminalIntegration;
import com.november.mcphone.feature.terminal.integration.TerminalSource;
import com.november.mcphone.feature.terminal.integration.Terminals;
import com.november.mcphone.feature.terminal.menu.TerminalSlotMenu;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 找一台终端并打开它 —— 「终端」这一格的全部业务逻辑。
 *
 * 它为什么这么短
 *
 * 因为它一件事都没自己做。范围、维度、耗电、绑的是哪个网络、增幅卡加了多远、界面长什么样，
 * 全部在终端物品自己的打开方法里，是 AE2 / RS / Tom's 各自的实现。我们只负责"挑出哪一台，
 * 然后按<b>它自己的</b>方式打开"，而"按它自己的方式"这一步也不在这里，在
 * {@link TerminalIntegration} 的各个实现里。
 *
 * 挑哪一台：两级
 *
 *   一、手机卡槽里装了终端 → 就是它。玩家自己选的，没有歧义
 *   二、卡槽空着 → 退回背包里第一台（快捷栏天然优先）
 *
 * 第二级不是过渡措施，是刻意留着的。终端一旦被收进手机就不在背包里了，于是各家自己的
 * 快捷键（AE2WTLib 的补货/磁铁/收纳、RS 的打开无线网格）都找不到它。把卡槽做成
 * <b>加成而不是替代</b>，这些东西才不会被我们顺手弄坏——想用快捷键的人不装卡槽就是了，
 * 代价只是点开 App 之后多按一下「打开终端」。
 *
 * 谁走得到第二级
 *
 * 只有卡槽界面上那个「打开终端」按钮。<b>点 App 图标走不到</b>：卡槽空着时客户端直接开
 * 卡槽界面（见 {@code TerminalApp.onPress}）。原来点一下就把背包里那台开出来，结果是身上
 * 带着终端的玩家永远到不了卡槽界面——那一页只剩 Shift 一个没人猜得到的入口，也就没人装
 * 得进去。
 *
 * 两条路都没有终端时开卡槽界面，而不是甩一句"你没有终端"：那种时候把卡槽摆到玩家面前，
 * 他至少看得见这个机制存在。
 */
public final class TerminalOpener {

    private TerminalOpener() {}

    /** 开一台终端：按上面那两级挑。卡槽界面的「打开终端」按钮走这条，点 App 只在卡槽装了终端时走 */
    public static void open(ServerPlayer player) {
        // 开之前先给卡槽里那台补满电，"没电打不开"这件事因此根本不会发生。空着、不吃电、
        // 服主关了开关都会直接返回，见 TerminalCharger
        TerminalCharger.topUp(player);

        // 一、手机卡槽
        if (openIfPossible(player, TerminalSlot.get(player), TerminalSource.phoneSlot())) return;

        // 二、背包。顺序就是槽位顺序，所以快捷栏（0-8）优先，副手与盔甲位排在最后
        // ——Inventory.getItem 的编号覆盖这三块，而各家认的也是同一套编号。
        Inventory inventory = player.getInventory();
        ItemStack incapable = ItemStack.EMPTY;

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);

            // 认得出、但这一类终端根本没有"隔空打开"这回事（Tom's 的基础无线终端）。
            // 记下第一台，接着往下找——身上可能还带着一台开得了的。
            if (Terminals.isRemoteIncapable(stack)) {
                if (incapable.isEmpty()) incapable = stack;
                continue;
            }

            if (openIfPossible(player, stack, TerminalSource.inventorySlot(slot))) return;
        }

        // 三、哪儿都没有。把卡槽摆出来，比一句"你没有终端"有用
        if (!incapable.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("mcphone.terminal.no_remote", incapable.getHoverName()), false);
        }
        openSlotMenu(player);
    }

    /** 打开终端卡槽界面 */
    public static void openSlotMenu(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (containerId, playerInventory, ignored) -> new TerminalSlotMenu(containerId, playerInventory),
                Component.translatable("mcphone.terminal.slot_title")));
    }

    /**
     * 这一台能开就开，返回开成功了没有。
     *
     * 对方答 false 的时候（没电、没绑定之类）不在这里补消息：它自己已经跟玩家说过了，
     * 我们再说一句就成了两条互相矛盾的提示。返回 false 会让上面那个循环接着往下找——身上
     * 带着两台、第一台没电时，第二台照样开得出来。
     */
    private static boolean openIfPossible(ServerPlayer player, ItemStack stack, TerminalSource source) {
        Optional<TerminalIntegration> owner = Terminals.owner(stack);
        if (owner.isEmpty()) return false;

        TerminalIntegration integration = owner.get();
        try {
            return integration.canOpen(stack) && integration.open(player, stack, source);
        } catch (Throwable t) {
            // 兜住，理由和 Terminals.discover 那一处一样：对方改了 API 时抛的是
            // NoSuchMethodError / NoClassDefFoundError（属于 Error），而这里跑在
            // enqueueWork 排给主线程的任务里 —— 让它冒出去就是一次服务端崩溃，
            // 起因只是某个玩家点了一下手机上的 App。
            MCphone.LOGGER.error("用 {} 打开终端时出错", integration.modId(), t);
            player.displayClientMessage(
                    Component.translatable("mcphone.terminal.open_failed", integration.displayName()), false);
            return true;   // 已经给过反馈了，别再往下找、也别再开卡槽界面
        }
    }
}
