package com.november.mcphone.platform;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * 药水效果的查询与授予（施工方案 §18.1 第 2 层）。<b>1.21.1 这一支</b>。
 *
 * <p>效果是<b>有时限</b>的：{@code MobEffectInstance} 自带时长，到期自然消失，不写存档，
 * 所以不存在"授权过期了效果还在"的漏洞 —— 和 {@code attr.grant} 的 Transient 同一个道理。
 *
 * <p>1.20.1 的取法走 {@code ForgeRegistries.MOB_EFFECTS}（效果 id → 效果对象），
 * 1.20.3+ 是 {@code Holder<MobEffect>} —— 构造参数那一处两版不同，所以这门面要按平台分。
 * 调它的只有主线程上的意图落地（{@code ServerIntentApplier}）。
 */
public final class PlayerEffects {

    private PlayerEffects() {
    }

    /** 本服有没有这个效果（注册表里查得到就认）。 */
    public static boolean available(ResourceLocation effectId) {
        return holder(effectId) != null;
    }

    /** 给玩家一个有时限的效果；效果 id 认不得返回 false（调用方先 {@link #available} 判过）。 */
    public static boolean give(ServerPlayer player, ResourceLocation effectId, int durationTicks, int amplifier) {
        Holder<MobEffect> holder = holder(effectId);
        if (holder == null) return false;
        return player.addEffect(new MobEffectInstance(holder, durationTicks, amplifier));
    }

    private static Holder<MobEffect> holder(ResourceLocation id) {
        return BuiltInRegistries.MOB_EFFECT.getHolder(id).orElse(null);
    }
}
