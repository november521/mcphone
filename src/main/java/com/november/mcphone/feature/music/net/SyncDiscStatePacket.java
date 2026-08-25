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
 * S2C：唱片仓现在是什么样。下发整张 ItemStack 是因为界面要画那张唱片的图标。
 *
 * @param disc       空栈表示没放
 * @param endsAtTick 外放放到哪一个游戏刻为止，{@link DiscState#NOT_PLAYING} 表示没在放；
 *                   给终点而不是布尔量：服务端没有 tick 盯着放完，布尔量会无声过期
 */
public record SyncDiscStatePacket(ItemStack disc, long endsAtTick)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncDiscStatePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "sync_disc_state"));

    /** 要 RegistryFriendlyByteBuf（物品编解码查注册表）；OPTIONAL 那一版是因为 ItemStack.STREAM_CODEC 不接受空栈 */
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
