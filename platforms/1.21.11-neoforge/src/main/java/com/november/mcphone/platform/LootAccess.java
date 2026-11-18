package com.november.mcphone.platform;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.List;

/**
 * 战利品表的读取与掷取（施工方案 §18.2）。<b>1.21.1 这一支</b>。
 *
 * <p>1.20.1 没有 {@code reloadableRegistries()}、键类型也不同，那一支自己查
 * {@code getLootData()}；掷取的那一段两边逐字相同，但取表这一步换了写法 ——
 * 这正是 {@code platform/} 要收的东西。
 *
 * <p>调它的只有主线程上的意图落地（{@code ServerIntentApplier}）：{@code getRandomItems}
 * 会读服主的随机数源，不在 worker 上调。
 */
public final class LootAccess {

    private LootAccess() {
    }

    /** 本服有没有这张表（服主数据包改过之后按当前注册表判）。 */
    public static boolean exists(ServerLevel level, ResourceLocation id) {
        return table(level, id) != LootTable.EMPTY;
    }

    /**
     * 按 {@code GIFT} 参数集掷一张表。表不存在返回空表（调用方先 {@link #exists} 判过）。
     * <b>不把产出直接给玩家</b>：给物品是落地端的事，这里只掷。
     */
    public static List<ItemStack> roll(ServerLevel level, ResourceLocation id, ServerPlayer player) {
        LootTable table = table(level, id);
        if (table == LootTable.EMPTY) return List.of();
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, player.position())
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .create(LootContextParamSets.GIFT);
        return List.copyOf(table.getRandomItems(params));
    }

    private static LootTable table(ServerLevel level, ResourceLocation id) {
        return level.getServer().reloadableRegistries()
                .getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id));
    }
}
