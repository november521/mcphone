package com.november.mcphone.platform;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 药水效果的查询与授予（施工方案 §18.1 第 2 层）。<b>1.20.1 这一支</b>。
 *
 * <p>1.21.1 拿的是 {@code Holder<MobEffect>}、构造也吃 Holder；这一支是
 * {@code ForgeRegistries.MOB_EFFECTS.getValue(id)} + 吃 {@code MobEffect} 的旧构造 ——
 * 差异只在"怎么拿到效果"和构造参数，语义两边一致（有时限、不写存档）。
 *
 * <p>调它的只有主线程上的意图落地（{@code ServerIntentApplier}）。
 */
public final class PlayerEffects {

    private PlayerEffects() {
    }

    /** 本服有没有这个效果（注册表里查得到就认）。 */
    public static boolean available(ResourceLocation effectId) {
        return ForgeRegistries.MOB_EFFECTS.getValue(effectId) != null;
    }

    /** 给玩家一个有时限的效果；效果 id 认不得返回 false（调用方先 {@link #available} 判过）。 */
    public static boolean give(ServerPlayer player, ResourceLocation effectId, int durationTicks, int amplifier) {
        MobEffect effect = ForgeRegistries.MOB_EFFECTS.getValue(effectId);
        if (effect == null) return false;
        return player.addEffect(new MobEffectInstance(effect, durationTicks, amplifier));
    }
}
