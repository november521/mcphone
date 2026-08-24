package com.november.mcphone.feature.music;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.ItemStack;

/**
 * 手机唱片仓里的东西 —— 跟着玩家走的数据，存为附件。
 *
 * 为什么"在不在放"只存一个开始时刻
 *
 * 不存 boolean。存了就要有人在放完的那一刻把它改回 false，也就是服务端
 * 每 tick 去查一遍所有人的唱片有没有到点——为一件没人在看的事每 tick
 * 扫一遍全服，不值得。
 *
 * 存开始时刻则什么都不用做："现在还在不在放"是一道算术题：
 *
 *     startedTick >= 0 && 现在的游戏刻 < startedTick + 这张唱片的长度
 *
 * 声音那头也不需要谁去停：原版音效放完自己就没了，服务端本来就不必
 * 插手。只有玩家【主动停】或者【取出唱片】时才要发一个停止包。
 *
 * 用游戏刻而不是系统时间
 *
 * 唱片长度 JukeboxSong.lengthInTicks() 给的是刻，两边同一个单位才不用
 * 换算。而且游戏刻是服务端权威的，玩家改自己电脑的钟也影响不到它。
 *
 * @param disc        仓里那张唱片；空栈表示没放
 * @param startedTick 开始外放的游戏刻；**-1 表示没在放**
 */
public record DiscState(ItemStack disc, long startedTick) {

    /**
     * "没在放"。startedTick 与下发给客户端的终点刻共用这一个哨兵值 ——
     * 两边各写各的 -1 迟早会有一边改了另一边没跟上。
     */
    public static final long NOT_PLAYING = -1L;

    /** 没放唱片、也没在放 */
    public static final DiscState EMPTY = new DiscState(ItemStack.EMPTY, NOT_PLAYING);

    public static final Codec<DiscState> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    // OPTIONAL_CODEC 而不是 CODEC：空栈是常态（大多数玩家
                    // 兜里那部手机里没唱片），而 ItemStack.CODEC 不接受空栈
                    ItemStack.OPTIONAL_CODEC.fieldOf("disc").forGetter(DiscState::disc),
                    Codec.LONG.fieldOf("started_tick").forGetter(DiscState::startedTick)
            ).apply(instance, DiscState::new)
    );

    public boolean hasDisc() {
        return !disc.isEmpty();
    }

    /** 换一张唱片，同时停掉正在放的 —— 换碟当然要从头来 */
    public DiscState withDisc(ItemStack stack) {
        return new DiscState(stack, -1L);
    }

    /** 从这一刻开始放 */
    public DiscState playingSince(long tick) {
        return new DiscState(disc, tick);
    }

    public DiscState stopped() {
        return startedTick < 0 ? this : new DiscState(disc, -1L);
    }
}
