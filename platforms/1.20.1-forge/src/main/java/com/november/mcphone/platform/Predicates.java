package com.november.mcphone.platform;

import com.november.mcphone.MCphone;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 数据包谓词的查询与判定（施工方案 §18.3）。<b>1.20.1 这一支</b>。
 *
 * <p>1.21.1 走 {@code reloadableRegistries().get().registryOrThrow(Registries.PREDICATE)}；
 * 这一支是 {@code getLootData().getElement(LootDataType.PREDICATE, id)} —— 差别只在
 * "怎么拿到那个谓词"，判定那一段两边逐字相同。
 *
 * <p>判定用和战利品表同一套参数（{@code GIFT}：{@code ORIGIN} + {@code THIS_ENTITY}），
 * <b>只读</b>。1.20.1 的 Builder 只有 {@code withOptionalRandomSeed(long)}，用本线程
 * 新开的种子，不去碰世界的那一份。
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
                    .withOptionalRandomSeed(ThreadLocalRandom.current().nextLong())
                    .create(null);
            return condition.test(context);
        } catch (RuntimeException e) {
            MCphone.LOGGER.warn("[MCphone] 谓词判定失败（按判否处理）：{} —— {}", id, e.toString());
            return Boolean.FALSE;
        }
    }

    private static LootItemCondition condition(ServerPlayer player, ResourceLocation id) {
        return player.getServer().getLootData().getElement(LootDataType.PREDICATE, id);
    }
}
