package com.november.mcphone.platform;

import com.november.mcphone.compat.CuriosCompat.CurioSlotRef;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.function.Predicate;

public final class CuriosInventories {
    private CuriosInventories() {}
    public static boolean isSupported() { return false; }
    public static boolean isEquipped(LivingEntity entity, Predicate<ItemStack> filter) { return false; }
    public static Optional<CurioSlotRef> find(LivingEntity entity, Predicate<ItemStack> filter) { return Optional.empty(); }
    public static ItemStack get(LivingEntity entity, String slotId, int index) { return ItemStack.EMPTY; }
    public static void set(LivingEntity entity, String slotId, int index, ItemStack stack) {}
}
