package com.november.mcphone.api.cost;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * 从背包里扣物品的代价。用谓词而不是直接认物品种类：有些区别在数据组件上，
 * 比如空白的书与笔和写过字的是同一种物品。多数情况用 {@link ICost#of} 就够。
 */
public record ItemCost(Predicate<ItemStack> filter, int count,
                       Component description) implements ICost {

    @Override
    public boolean canAfford(Player player) {
        if (player.getAbilities().instabuild) return true;
        return countIn(player) >= count;
    }

    /** 先数够不够再动手：扣到一半发现不够会让玩家白白损失前半截物品 */
    @Override
    public boolean consume(Player player) {
        if (player.getAbilities().instabuild) return true;
        if (countIn(player) < count) return false;

        int remaining = count;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !filter.test(stack)) continue;

            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        return true;
    }

    @Override
    public Component describe() {
        return description;
    }

    /** 身上一共有几个符合条件的。遍历全部隔间，盔甲栏与副手也算 */
    private int countIn(Player player) {
        Inventory inventory = player.getInventory();
        int found = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && filter.test(stack)) found += stack.getCount();
        }
        return found;
    }
}
