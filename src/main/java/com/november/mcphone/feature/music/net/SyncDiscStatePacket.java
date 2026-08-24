package com.november.mcphone.feature.music.net;

import com.november.mcphone.MCphone;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import com.november.mcphone.feature.music.DiscState;
import net.minecraft.world.item.ItemStack;

/**
 * 网络包：服务端 → 客户端，唱片仓现在是什么样。
 *
 * 下发整张 ItemStack 而不是一个名字：界面要画那张唱片的图标，而图标只能
 * 从物品本身来。顺带也就支持了别的模组的唱片——我们不必认识它长什么样。
 *
 * @param disc       仓里那张唱片，空栈表示没放
 * @param endsAtTick 外放会放到哪一个游戏刻为止；{@link DiscState#NOT_PLAYING}
 *                   表示没在放。
 *
 *                   1.5.14 之前这里是一个 boolean。那个值会过期而且过期得
 *                   无声无息：服务端没有任何 tick 盯着唱片放完，放完的那一
 *                   刻没人会通知客户端，而这个包只在玩家主动操作时才回。
 *                   于是界面上那个键一直停在"停止"的样子，点它反倒把唱片
 *                   从头又放一遍。给终点，客户端就能自己算到点没到点 ——
 *                   游戏刻是服务端权威的，客户端那一份跟着同步，两边做的
 *                   是同一道算术。
 */
public record SyncDiscStatePacket(ItemStack disc, long endsAtTick)
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
                    ByteBufCodecs.VAR_LONG, SyncDiscStatePacket::endsAtTick,
                    SyncDiscStatePacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
