package com.november.mcphone.feature.terminal;

import com.november.mcphone.core.ServerConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 手机替卡槽里那台终端供电 —— 装进去就不会没电。
 *
 * 它解决的是什么
 *
 * 终端装进手机卡槽之后，充电这件事变得很难受：卡槽是玩家附件，世界里的充电器够不着它，
 * 于是"没电了"的唯一出路是<b>把它从卡槽里取出来、充完、再装回去</b>。而没电的终端连开都
 * 开不了（AE2 自己会拒绝），所以这一趟躲不掉。装进手机反而比揣在背包里麻烦，那这个卡槽
 * 就白做了。
 *
 * 我们只管「电」这一件事
 *
 * 范围、维度、绑没绑网络、增幅卡加了多远，一条都没动，仍然是 AE2 / RS 自己查、自己拒绝、
 * 自己给玩家发消息（见 {@link TerminalOpener}）。这里也没有碰它们的菜单：充电走的是
 * <b>NeoForge 的物品能量能力</b>，AE2 与 RS 都为自己的终端注册了它
 * （{@code InitCapabilityProviders.initPoweredItem} → {@code PoweredItemCapabilities}；
 * {@code EnergyStorageAdapter}），我们调的是它们自己那份实现，和拿去充电器里充是同一条路。
 * 认能力而不认牌子，所以这个类里一个 AE2 / RS 的类型都没有，将来谁家的终端只要挂了这个
 * 能力就自动跟着享受。
 *
 * Tom's Simple Storage 无事发生：它的终端整个模组里连能量的影子都没有，本来就不用电。
 *
 * 这不是一台无限发电机
 *
 * 充进去的电<b>取不回来</b>：AE2 与 RS 的那两份实现 {@code canExtract()} 都恒为 false
 * （AE2 的 {@code extractEnergy} 方法体就是一句 {@code return 0}）。所以没法拿终端当电池，
 * 从卡槽里取出来插进机器抽电这条路不通。
 *
 * 服主不想要可以关：{@code serverconfig/mcphone-server.toml} 里的 {@code terminalKeepPowered}。
 * 关掉之后卡槽里的终端和拿在手上完全一样，该耗多少耗多少。
 *
 * 什么时候补，以及为什么只有这两个时候
 *
 * <b>一、开的那一刻</b>（{@link TerminalOpener#open}）。这一下是主要目的：不管之前是什么
 * 状态，点下去永远开得起来。
 *
 * <b>二、有容器界面开着的时候，每秒一次。</b>关键在于——<b>没人用的时候那台终端的电量根本
 * 不会变</b>：AE2 是在菜单开着时每 tick 扣（{@code WirelessTerminalMenuHost.tick()} →
 * {@code consumeIdlePower}），RS 干脆是按操作扣。所以走路、挖矿、界面全关着的时候补电是在
 * 查一个永远不变的值，这一档<b>刻意不做</b>。
 *
 * 界面开着时为什么是每秒，而不是更慢
 *
 * 这一档是被 <b>RS</b> 定的，不是 AE2。两家的量级差着三个数量级：
 *
 *   AE2   电池 1,600,000 AE，每 tick 扣的是"离接入点的距离"（默认倍率 1.0），也就是每秒
 *         20×距离。基地里 16 格＝320 AE/s，跑到 500 格外也才 1 万 AE/s——满电够连续用
 *         160 秒以上。光看它，一分钟补一次都绰绰有余。
 *   RS    容量默认 <b>1000</b>，开一次 5、存一次 5、取一次 5，<b>总共只够 200 次操作</b>。
 *         一轮连续 shift 点击几十秒就能见底。
 *
 * 所以档位按 RS 取：一秒的窗口里最多丢 20 次操作的余量，接得住。真按 30 秒、60 秒来，RS
 * 玩家会遇到"点着点着突然断了，还得等最多一分钟"——而那正是这个功能要消灭的体验。
 *
 * "有容器界面开着"是个粗判（{@code containerMenu != inventoryMenu}）：开着箱子、以及开着
 * 我们自家那张卡槽界面时也会补一次——所以从卡槽界面把终端拖进去，它一秒内就满了。
 * 代价是一次能力查询，值没变就直接返回、连同步包都不发。要精确判断"开着的是不是那台终端的
 * 菜单"得去认三家各自的菜单类型，跨模组、还会随它们改版失效，不值。
 */
public final class TerminalCharger {

    private TerminalCharger() {}

    /** 界面开着时多久补一次，单位 tick */
    private static final int INTERVAL_TICKS = 20;

    /** 由 MCphone 构造函数挂到游戏总线 */
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

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
        IEnergyStorage energy = terminal.getCapability(Capabilities.EnergyStorage.ITEM);
        if (energy == null || !energy.canReceive()) return;

        int missing = energy.getMaxEnergyStored() - energy.getEnergyStored();
        if (missing <= 0) return;

        // 满了就不必再同步。这一句也顺带兜住"能力说得收、实际一点都收不进"的实现
        if (energy.receiveEnergy(missing, false) > 0) TerminalSlot.markChanged(player);
    }
}
