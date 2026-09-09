package com.november.mcphone.feature.terminal.net;

import com.november.mcphone.feature.terminal.TerminalSlot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

/**
 * S2C：手机卡槽里现在装着哪一台终端。空栈表示没装。
 *
 * <h2>这个包在 NeoForge 那一支上不存在</h2>
 *
 * 那边卡槽是 {@code AttachmentType.builder(...).sync(ItemStack.OPTIONAL_STREAM_CODEC)}，
 * 加载器负责把它推给客户端。Forge 1.20.1 的 capability 没有这回事，所以这一支得自己发。
 * 发的时机见 {@link TerminalSlot}：上线、重生、换维度，以及内容变了的每一次。
 *
 * 客户端非知道不可的理由也在那儿——AE2 的终端菜单要在客户端重建，重建时会在客户端再问
 * 一次"那台终端在哪儿"。客户端答不上来，菜单当场判失效关掉。
 *
 * <h2>为什么是整张 ItemStack</h2>
 *
 * 客户端要拿它做三件事：卡槽界面上画那台终端的图标、决定「打开终端」按钮亮不亮、以及
 * 上面说的那次 host 重建。前两件要图标与名字，第三件要物品本身，一个布尔量都办不了。
 *
 * 1.20.1 的 {@code writeItem/readItem} 就在普通的 {@link FriendlyByteBuf} 上，而且
 * <b>本来就允许空栈</b>（它先写一个 present 布尔位）；1.21.1 那边要
 * RegistryFriendlyByteBuf 加 OPTIONAL_STREAM_CODEC。所以这里比那边简单，
 * 和 {@code SyncDiscStatePacket} 是同一条路。
 */
public record SyncTerminalSlotPacket(ItemStack terminal) {

    public static void encode(SyncTerminalSlotPacket msg, FriendlyByteBuf buf) {
        buf.writeItem(msg.terminal());
    }

    public static SyncTerminalSlotPacket decode(FriendlyByteBuf buf) {
        return new SyncTerminalSlotPacket(buf.readItem());
    }
}
