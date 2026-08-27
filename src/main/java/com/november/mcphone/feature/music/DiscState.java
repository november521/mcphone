package com.november.mcphone.feature.music;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.ItemStack;

/**
 * 手机唱片仓里的东西，存为玩家附件。
 * "在不在放"不存 boolean 而存开始时刻：服务端没有 tick 盯着唱片放完，现算 startedTick + 长度 即可；
 * 用游戏刻不用系统时间：与 JukeboxSong.lengthInTicks 同单位，且服务端权威。
 *
 * @param disc        空栈表示没放
 * @param startedTick 开始外放的游戏刻，-1 表示没在放
 */
public record DiscState(ItemStack disc, long startedTick) {

    /** "没在放"哨兵；startedTick 与下发给客户端的终点刻共用这一个值 */
    public static final long NOT_PLAYING = -1L;

    public static final DiscState EMPTY = new DiscState(ItemStack.EMPTY, NOT_PLAYING);

    public static final Codec<DiscState> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    // 1.21 那边用 ItemStack.OPTIONAL_CODEC —— 1.20.5 才有的东西。
                    // 1.20.1 只有不接受空栈的 ItemStack.CODEC，而空栈（仓里没唱片）
                    // 恰恰是常态，所以改成 optionalFieldOf：没唱片就【不写这个字段】，
                    // 读回来是 Optional.empty，语义与那边的空栈一致
                    ItemStack.CODEC.optionalFieldOf("disc")
                            .forGetter(s -> s.disc().isEmpty()
                                    ? java.util.Optional.<ItemStack>empty()
                                    : java.util.Optional.of(s.disc())),
                    Codec.LONG.fieldOf("started_tick").forGetter(DiscState::startedTick)
            ).apply(instance, (disc, tick) -> new DiscState(disc.orElse(ItemStack.EMPTY), tick))
    );

    public boolean hasDisc() {
        return !disc.isEmpty();
    }

    /** 换一张唱片，同时停掉正在放的 —— 换碟当然要从头来 */
    public DiscState withDisc(ItemStack stack) {
        return new DiscState(stack, -1L);
    }

    public DiscState playingSince(long tick) {
        return new DiscState(disc, tick);
    }

    public DiscState stopped() {
        return startedTick < 0 ? this : new DiscState(disc, -1L);
    }
}
