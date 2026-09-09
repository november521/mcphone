package com.november.mcphone.feature.terminal.integration.refinedstorage;

import com.november.mcphone.feature.terminal.integration.TerminalIntegration;
import com.november.mcphone.feature.terminal.integration.TerminalSource;
import com.refinedmods.refinedstorage.api.network.grid.IGridManager;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.network.grid.factory.PortableGridGridFactory;
import com.refinedmods.refinedstorage.inventory.player.PlayerSlot;
import com.refinedmods.refinedstorage.item.NetworkItem;
import com.refinedmods.refinedstorage.item.blockitem.PortableGridBlockItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Refined Storage 的接入 —— 无线网格、无线合成监视器、便携网格。
 *
 * <h2>1.20.1 上的 RS 是 1.12.x，不是 RS 2</h2>
 *
 * 这一条决定了下面所有的形状。{@code main}（NeoForge 1.21.1）对的是 <b>Refined Storage 2</b>
 * （2.0.x），这一支对的是 <b>1.12.x</b>——同一个模组、隔了一整代的 API，不是"版本号不同"
 * 那么简单：
 *
 * <pre>
 *   RS 2（那边）   SlotReferenceHandlerItem#use(玩家, 物品, SlotReference)
 *                  SlotReference 是【可注册】的一套，自己实现一种就能表达"在手机卡槽里"
 *
 *   RS 1.12（这边） NetworkItem#applyNetwork(...) → INetworkItemManager#open(玩家, 物品, PlayerSlot)
 *                  PlayerSlot 是个【具体类】，字段只有 int slot 与 String curioSlot
 * </pre>
 *
 * <h2>所以这一支上 RS 的终端装不进手机卡槽</h2>
 *
 * {@link #canLiveInPhoneSlot()} 答 false，卡槽的 mayPlace 直接不收它。这是<b>功能上的
 * 降级</b>，不是等价替换，写在这里是为了下次有人来问的时候不用重新查一遍：
 *
 * <ul>
 *   <li>{@code PlayerSlot} 没有留任何注册点，它的 {@code getStackFromSlot} 写死了两条路
 *       ——Curios 的某个槽，或者 {@code player.getInventory().getItem(slot)}</li>
 *   <li>继承它、覆盖 {@code getStackFromSlot} 是能编过的，但<b>过不了网</b>：它的线上格式
 *       是一个 int 加一个可选字符串，客户端收到之后 {@code new PlayerSlot(buf)} 造出来的
 *       是<b>原版那个类</b>，我们的覆盖不在了。而 {@code WirelessCraftingMonitorContainerFactory}
 *       （{@code IContainerFactory}，跑在客户端）拿到它第一句就是 {@code getStackFromSlot}
 *       ——传一个哨兵槽位号（-1 之类）进去，那一句在客户端就是
 *       {@code NonNullList.get(-1)}，玩家看到的是崩溃，不是"这一格不好使"</li>
 *   <li>实测过的两头：{@code refinedstorage-1.12.0} 与 {@code 1.12.4}，PlayerSlot 的字段与
 *       方法一字未变</li>
 * </ul>
 *
 * 背包里那台<b>照常开得了</b>——那正是 {@code PlayerSlot(int)} 生来要表达的东西。所以玩家
 * 这边的差别只有一条：RS 的终端得留在背包里，不能收进手机。
 *
 * <h2>认领：两个 instanceof，不是一个</h2>
 *
 * RS 2 把"能被远程唤起的随身物件"抽象成了一个接口，1.12 没有那个接口：
 *
 * <pre>
 *   NetworkItem            无线网格、无线流体网格、无线合成监视器的共同基类
 *   PortableGridBlockItem  便携网格，它是个方块物品，走的是另一条打开路径
 * </pre>
 *
 * 所以这里认两次，开的时候也分两条——这不是我们的分类，是 RS 自己
 * {@code OpenNetworkItemMessage} 里的那两条分支，照抄的。
 *
 * <h2>电量、绑没绑网络</h2>
 *
 * 一条都不在这里。{@code applyNetwork} 里 RS 自己查，查不过它自己给玩家发消息。
 */
public final class RefinedStorageIntegration implements TerminalIntegration {

    public static final String MODID = "refinedstorage";

    /** 显示名。与 modid 一样是编译期常量，理由见 {@code Ae2Integration.NAME} */
    public static final String NAME = "Refined Storage";

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
        return stack.getItem() instanceof NetworkItem
                || stack.getItem() instanceof PortableGridBlockItem;
    }

    /** 装不进手机卡槽，理由见类注释。能不能<b>打开</b>是另一问，那个照常答是 */
    @Override
    public boolean canLiveInPhoneSlot() {
        return false;
    }

    /**
     * 打开背包里那一台。
     *
     * 卡槽那一种到不了这里——{@link #canLiveInPhoneSlot()} 已经把它挡在放进去之前了。
     * 这里仍然写一句 return false 而不是抛：万一哪天有别的路径绕过卡槽调进来，
     * 返回 false 会让 {@code TerminalOpener} 接着往背包里找，比抛异常体面。
     *
     * 两条分支照抄 RS 自己 {@code OpenNetworkItemMessage.handle} 的形状：
     * {@code NetworkItem} 走网络物品管理器，便携网格走 grid 管理器。第二个参数那个
     * {@code Consumer<Component>} 是"出错了怎么告诉玩家"，RS 自己传的就是玩家的
     * {@code sendSystemMessage}。
     */
    @Override
    public boolean open(ServerPlayer player, ItemStack stack, TerminalSource source) {
        if (!(source instanceof TerminalSource.InventorySlot inventorySlot)) return false;

        PlayerSlot slot = new PlayerSlot(inventorySlot.index());

        if (stack.getItem() instanceof NetworkItem networkItem) {
            networkItem.applyNetwork(
                    player.server,
                    stack,
                    network -> network.getNetworkItemManager().open(player, stack, slot),
                    player::sendSystemMessage);
            return true;
        }

        if (stack.getItem() instanceof PortableGridBlockItem) {
            IGridManager grids = API.instance().getGridManager();
            grids.openGrid(PortableGridGridFactory.ID, player, stack, slot);
            return true;
        }

        return false;
    }
}
