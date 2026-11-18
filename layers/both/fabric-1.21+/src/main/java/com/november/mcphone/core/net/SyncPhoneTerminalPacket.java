package com.november.mcphone.core.net;

import com.november.mcphone.MCphone;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 网络包：服务端 → 客户端，同步"手机终端卡槽里那台终端"。
 *
 * NeoForge 版不需要这个包：PHONE_TERMINAL 附件带 {@code sync()}，附件一变
 * NeoForge 自动推给客户端。Fabric 1.21.1 的附件 API 没有同步（sync 是
 * 1.4.0+ 才有的），这份同步手工补：
 *
 *   - 写入侧：{@code PhonePlayerData.setTerminal} 在服务端每次写完发一份
 *   - 登录侧：玩家进世界时随配置一起推一次初值
 *   - 接收侧：客户端把附件原样写到本地玩家身上，AE2 / RS 终端菜单在客户端
 *     重建时才有得问"那台终端在哪儿"（见 TerminalSlot 的类注释）
 */
public record SyncPhoneTerminalPacket(ItemStack terminal) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncPhoneTerminalPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "sync_phone_terminal"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncPhoneTerminalPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ItemStack.OPTIONAL_STREAM_CODEC,
                    SyncPhoneTerminalPacket::terminal,
                    SyncPhoneTerminalPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
