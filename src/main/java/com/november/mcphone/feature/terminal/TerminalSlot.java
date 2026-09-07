package com.november.mcphone.feature.terminal;

import com.november.mcphone.core.ModCapabilities;
import com.november.mcphone.core.PhonePlayerData;
import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.feature.terminal.net.SyncTerminalSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;

/**
 * 装在手机里的那台终端 —— 存取的唯一入口。
 *
 * <h2>它不认牌子</h2>
 *
 * 里头放的就是一个 ItemStack，AE2 的无线终端、Tom's 的高级无线终端都放得进来。"这台归谁
 * 管"由 {@link com.november.mcphone.feature.terminal.integration.Terminals} 现问现答，不
 * 存在这里——存了就得考虑"玩家卸载了那个模组之后这一格里的记录怎么办"。
 *
 * （RS 的终端在这一支上装不进来，理由见
 * {@code RefinedStorageIntegration}；那是 1.20.1 特有的一处降级，不是这个类的事。）
 *
 * <h2>为什么是玩家数据，而不是手机物品上的 NBT</h2>
 *
 * 两个理由，第二个是硬的。
 *
 * 一、手机里的东西在这个模组里一律跟着玩家走：壁纸、笔记、聊天已读、唱片仓、买过的 App
 * 全在 {@link PhonePlayerData} 里。装在手机里的终端是同一类东西。
 *
 * 二、<b>物品 NBT 存不了它。</b>{@code ItemStack.getOrCreateTag()} 给的虽然是活的标签，但
 * 从里面读出一台终端只能是 {@code ItemStack.of(tag)}——每次读都是一份<b>新的副本</b>。而
 * AE2 会往终端那个 ItemStack 上<b>写</b>：耗电、你在终端界面里调的排序与视图。它拿的是
 * "位置"回头现取（{@code ItemMenuHost.getItemStack()}），所以只要给的是副本，那些写入就
 * 落在副本上然后被丢掉：终端永远不掉电、设置每次都还原，而且<b>不报任何错</b>。
 * 玩家数据里放的是同一个对象，写入落在真身上。
 *
 * <h2>同步：这一支要自己发包</h2>
 *
 * NeoForge 那边这一格是 {@code AttachmentType.builder(...).sync(...)}，加载器负责推给客户
 * 端。Forge 1.20.1 的 capability <b>没有</b>这回事，所以下面三个事件加 {@link #set} 里那
 * 一句就是全部的同步时机：上线、重生、换维度，以及内容变了的每一次。
 *
 * 少了它不是"界面上看不见"这么简单：AE2 的终端菜单在客户端要被重建一次，重建时会在
 * <b>客户端</b>再问一次"那台终端在哪儿"（见 {@code TerminalSlotLocator}）。客户端拿到空
 * 的，菜单当场判失效关掉——表现是"点了闪一下又回来"。
 */
public final class TerminalSlot {

    private TerminalSlot() {}

    /**
     * 手机里装的那台终端。没装则返回 {@link ItemStack#EMPTY}。
     *
     * 返回的是<b>活的</b>那一个，不是副本——存储模组往它上面写的耗电与设置要落在这里。
     * 别对返回值调 {@code copy()} 之后再交给它们。
     */
    public static ItemStack get(Player player) {
        return ModCapabilities.of(player).terminal();
    }

    /** 换掉手机里那台终端。服务端上调会顺手同步给该玩家的客户端 */
    public static void set(Player player, ItemStack stack) {
        ModCapabilities.of(player).setTerminal(stack);
        sync(player);
    }

    /**
     * 内容原地改过了，推一次同步。
     *
     * {@link #get} 给的是活对象，所以改它不会经过 {@link #set}，客户端那份不知道自己该变。
     * 槽位改动之后要显式叫一次。
     */
    public static void markChanged(Player player) {
        sync(player);
    }

    /**
     * 客户端收到同步包之后写进本地那份。
     *
     * 与 {@link #set} 分开只为一件事：那个会回头再发一次包。客户端调 {@code set} 会往
     * 服务端方向发一个 S2C 包，SimpleChannel 直接抛"wrong side"。
     */
    public static void applyFromServer(Player player, ItemStack stack) {
        ModCapabilities.of(player).setTerminal(stack);
    }

    private static void sync(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            MCphoneNetwork.sendToPlayer(serverPlayer, new SyncTerminalSlotPacket(get(player)));
        }
    }

    //  三个补同步的时机。由 MCphone 构造函数挂到 Forge 总线

    /** 上线：客户端那份是空的，得先给它一份 */
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        sync(event.getEntity());
    }

    /**
     * 重生：玩家换了个实体，capability 也是新的一份（内容由
     * {@link ModCapabilities#onPlayerClone} 拷过来），客户端并不知道。
     */
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        sync(event.getEntity());
    }

    /** 换维度：客户端整个重载了一遍世界，这一格跟着一起没了 */
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        sync(event.getEntity());
    }
}
