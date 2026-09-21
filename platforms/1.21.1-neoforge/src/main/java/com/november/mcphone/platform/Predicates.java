package com.november.mcphone.platform;

import com.november.mcphone.MCphone;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

import java.util.Optional;

/**
 * 数据包谓词的查询与判定（施工方案 §18.3）。<b>1.21.1 这一支</b>。
 *
 * <p>谓词住在可重载层：{@code reloadableRegistries().get().registryOrThrow(Registries.PREDICATE)}，
 * 服主改数据包、{@code /reload} 之后按当前注册表判。
 *
 * <p>判定用和战利品表同一套参数（{@code GIFT}：{@code ORIGIN} + {@code THIS_ENTITY}），
 * <b>只读</b>。随机源用本线程新开的，不去碰世界的那一份 —— 求值在 worker 上，
 * 不能和主线程抢同一个 {@code RandomSource}。
 *
 * <p>1.20.1 没有 {@code reloadableRegistries()}，取谓词走 {@code getLootData()}；
 * 判定那一段两边逐字相同。
 */
public final class Predicates {

    private Predicates() {
    }

    /** 本服有没有这个谓词。 */
    public static boolean exists(ServerPlayer player, ResourceLocation id) {
        return condition(player, id) != null;
    }

    /**
     * 判定一个数据包谓词。认不得的 id 返回 {@code null}（调用方回"本服没有这个谓词"，
     * 是配置错、不是判否）；求值出错返回 {@code false}（保守：守卫判否），异常不抛进脚本。
     */
    public static Boolean test(ServerPlayer player, ResourceLocation id) {
        LootItemCondition condition = condition(player, id);
        if (condition == null) return null;
        try {
            LootParams params = new LootParams.Builder((ServerLevel) player.level())
                    .withParameter(LootContextParams.ORIGIN, player.position())
                    .withParameter(LootContextParams.THIS_ENTITY, player)
                    .create(LootContextParamSets.GIFT);
            LootContext context = new LootContext.Builder(params)
                    .withOptionalRandomSource(RandomSource.create())
                    .create(Optional.empty());
            return condition.test(context);
        } catch (RuntimeException e) {
            MCphone.LOGGER.warn("[MCphone] 谓词判定失败（按判否处理）：{} —— {}", id, e.toString());
            return Boolean.FALSE;
        }
    }

    private static LootItemCondition condition(ServerPlayer player, ResourceLocation id) {
        RegistryAccess.Frozen access = player.getServer().reloadableRegistries().get();
        return access.registryOrThrow(Registries.PREDICATE).get(id);
    }
}
