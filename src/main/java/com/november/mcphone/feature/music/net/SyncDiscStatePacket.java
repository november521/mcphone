package com.november.mcphone.feature.music.net;

import com.november.mcphone.MCphone;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 网络包：服务端 → 客户端，唱片仓现在是什么样。
 *
 * 下发整张 ItemStack 而不是一个名字：界面要画那张唱片的图标，而图标只能
 * 从物品本身来。顺带也就支持了别的模组的唱片——我们不必认识它长什么样。
 *
 * @param disc    仓里那张唱片，空栈表示没放
 * @param playing 此刻是不是正在外放。由服务端算好（见 DiscService），
 *                客户端不自己推算——它不知道唱片有多长，也不该知道
 */
public record SyncDiscStatePacket(ItemStack disc, boolean playing)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncDiscStatePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "sync_disc_state"));

    /**
     * 用 RegistryFriendlyByteBuf：物品的编解码要查注册表（附魔、组件里的
     * 各种引用都是注册表条目），普通 ByteBuf 没有那份上下文。
     *
     * OPTIONAL 那一版而不是 ItemStack.STREAM_CODEC：空栈是常态，
     * 而后者不接受空栈。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncDiscStatePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ItemStack.OPTIONAL_STREAM_CODEC, SyncDiscStatePacket::disc,
                    ByteBufCodecs.BOOL, SyncDiscStatePacket::playing,
                    SyncDiscStatePacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
