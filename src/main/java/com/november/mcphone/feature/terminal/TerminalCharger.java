package com.november.mcphone.feature.terminal;

import com.november.mcphone.core.ServerConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.TickEvent;

/**
 * 手机替卡槽里那台终端供电 —— 装进去就不会没电。
 *
 * <h2>它解决的是什么</h2>
 *
 * 终端装进手机卡槽之后，充电这件事变得很难受：卡槽是玩家自己身上的数据，世界里的充电器
 * 够不着它，于是"没电了"的唯一出路是<b>把它从卡槽里取出来、充完、再装回去</b>。而没电的
 * 终端连开都开不了（AE2 自己会拒绝），所以这一趟躲不掉。装进手机反而比揣在背包里麻烦，
 * 那这个卡槽就白做了。
 *
 * <h2>我们只管「电」这一件事</h2>
 *
 * 范围、维度、绑没绑网络、增幅卡加了多远，一条都没动，仍然是 AE2 自己查、自己拒绝、自己
 * 给玩家发消息（见 {@link TerminalOpener}）。这里也没有碰它的菜单：充电走的是
 * <b>Forge 的物品能量能力</b>，和拿去充电器里充是同一条路。认能力而不认牌子，所以这个类里
 * 一个 AE2 的类型都没有，将来谁家的终端只要挂了这个能力就自动跟着享受。
 *
 * <b>这一支上的两头都核过字节码</b>（1.20.1 的版本与那一支不是同一代，类名也不一样）：
 *
 * <pre>
 *   AE2 15.0.10   appeng.items.tools.powered.powersink.PoweredItemCapabilities
 *   RS 1.12.0     com.refinedmods.refinedstorage.item.EnergyItem
 *                 + item.capabilityprovider.EnergyCapabilityProvider
 *                 （那一支引的 EnergyStorageAdapter 是 RS2 的名字，这边没有这个类）
 *   Tom's 1.6.1   整个 jar 里 0 处引用 IEnergyStorage —— 它的终端本来就不用电
 * </pre>
 *
 * RS 那一行只是记录：<b>这一支上 RS 的终端根本装不进卡槽</b>（见
 * {@code RefinedStorageIntegration}），所以实际走到这里的只有 AE2 那一家。
 *
 * <h2>这不是一台无限发电机</h2>
 *
 * 充进去的电<b>取不回来</b>：AE2 那份实现的 {@code canExtract()} 恒为 false
 * （{@code extractEnergy} 方法体就是一句 {@code return 0}）。所以没法拿终端当电池，
 * 从卡槽里取出来插进机器抽电这条路不通。
 *
 * 服主不想要可以关：{@code serverconfig/mcphone-server.toml} 里的 {@code terminalKeepPowered}。
 *
 * <h2>什么时候补，以及为什么只有这两个时候</h2>
 *
 * <b>一、开的那一刻</b>（{@link TerminalOpener#open}）。这一下是主要目的：不管之前是什么
 * 状态，点下去永远开得起来，"没电打不开"根本不会发生。
 *
 * <b>二、有容器界面开着的时候，每秒一次。</b>关键在于——<b>没人用的时候那台终端的电量根本
 * 不会变</b>：AE2 是在菜单开着时每 tick 扣（{@code WirelessTerminalMenuHost.tick()} →
 * {@code consumeIdlePower}）。所以走路、挖矿、界面全关着的时候补电是在查一个永远不变的值，
 * 这一档<b>刻意不做</b>。
 *
 * 每秒这一档在那一支上是被 <b>RS</b> 定的（它的电池容量默认只有 1000，两百次操作就见底），
 * 而这一支 RS 进不来，光看 AE2 的话一分钟补一次都绰绰有余（电池 1,600,000 AE，基地里
 * 16 格是 320 AE/s）。<b>仍然照抄每秒</b>：一次能力查询而已，值没变就直接返回、连同步包都
 * 不发；哪天 RS 那条路通了，这个档位不用回头再调。
 *
 * "有容器界面开着"是个粗判（{@code containerMenu != inventoryMenu}）：开着箱子、以及开着
 * 我们自家那张卡槽界面时也会补一次——所以从卡槽界面把终端拖进去，它一秒内就满了。要精确
 * 判断"开着的是不是那台终端的菜单"得去认各家的菜单类型，跨模组、还会随它们改版失效，不值。
 *
 * <h2>与那一支的差别只在两个 API</h2>
 *
 * 能力：那边是 {@code stack.getCapability(Capabilities.EnergyStorage.ITEM)} 直接给对象，
 * 这边是 {@code ForgeCapabilities.ENERGY} 给一个 {@code LazyOptional}，要 resolve 一次。
 * 事件：那边是 {@code PlayerTickEvent.Post}，这边 1.20.1 只有一个 {@code PlayerTickEvent}，
 * Pre 与 END 都从这儿进来，<b>不判 phase 会一秒补两次</b>。
 */
public final class TerminalCharger {

    private TerminalCharger() {}

    /** 界面开着时多久补一次，单位 tick */
    private static final int INTERVAL_TICKS = 20;

    /** 由 MCphone 构造函数挂到 Forge 总线 */
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // 1.20.1 的 PlayerTickEvent 一 tick 触发两次（Pre 与 END），不判就是白跑一半
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        // 界面全关着的时候电量不会变，见类注释
        if (player.containerMenu == player.inventoryMenu) return;
        if (player.tickCount % INTERVAL_TICKS != 0) return;

        topUp(player);
    }

    /**
     * 把卡槽里那台补满。开终端之前也走这一句，好让"没电打不开"这件事根本不会发生。
     *
     * 卡槽空着、里头那台不吃电（Tom's）、或者服主关了开关，都是直接返回。
     */
    public static void topUp(ServerPlayer player) {
        if (!ServerConfig.terminalKeepPowered()) return;

        ItemStack terminal = TerminalSlot.get(player);
        if (terminal.isEmpty()) return;

        // 没挂能量能力的（Tom's 的终端、以及任何不用电的东西）到这儿就结束了
        IEnergyStorage energy = terminal.getCapability(ForgeCapabilities.ENERGY).resolve().orElse(null);
        if (energy == null || !energy.canReceive()) return;

        int missing = energy.getMaxEnergyStored() - energy.getEnergyStored();
        if (missing <= 0) return;

        // 满了就不必再同步。这一句也顺带兜住"能力说得收、实际一点都收不进"的实现
        if (energy.receiveEnergy(missing, false) > 0) TerminalSlot.markChanged(player);
    }
}
