package com.november.mcphone.feature.music.net;

import net.minecraft.network.FriendlyByteBuf;
import com.november.mcphone.feature.music.DiscState;
import net.minecraft.world.item.ItemStack;

/**
 * S2C：唱片仓现在是什么样。下发整张 ItemStack 是因为界面要画那张唱片的图标。
 *
 * @param disc       空栈表示没放
 * @param endsAtTick 外放放到哪一个游戏刻为止，{@link DiscState#NOT_PLAYING} 表示没在放；
 *                   给终点而不是布尔量：服务端没有 tick 盯着放完，布尔量会无声过期
 */
public record SyncDiscStatePacket(ItemStack disc, long endsAtTick) {

    /**
     * 1.21.1 那边要 RegistryFriendlyByteBuf（物品编解码要查注册表），
     * 而且得用 OPTIONAL_STREAM_CODEC，因为 ItemStack.STREAM_CODEC 不收空栈。
     *
     * 1.20.1 上两件事都不用操心：writeItem/readItem 就在普通的 FriendlyByteBuf 上，
     * 而且【本来就允许空栈】——它先写一个 present 布尔位。所以这里比那边简单。
     */
    public static void encode(SyncDiscStatePacket msg, FriendlyByteBuf buf) {
        buf.writeItem(msg.disc());
        buf.writeVarLong(msg.endsAtTick());
    }

    public static SyncDiscStatePacket decode(FriendlyByteBuf buf) {
        return new SyncDiscStatePacket(buf.readItem(), buf.readVarLong());
    }

}
