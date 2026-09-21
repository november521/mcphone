package com.november.mcphone.platform;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 属性修饰符的授予与撤销（施工方案 §18.4）。<b>1.20.1 这一支</b>。
 *
 * <p>三处与 1.21.1 不同，都收在这里：属性查 {@code ForgeRegistries.ATTRIBUTES}；
 * 修饰符是 {@code (UUID, String name, double, Operation)} 的类而不是 record，
 * 标识是 {@code UUID} 不是 {@code ResourceLocation}；枚举名是
 * {@code ADDITION / MULTIPLY_BASE / MULTIPLY_TOTAL}。
 *
 * <p>{@code ResourceLocation → UUID} 用 {@code nameUUIDFromBytes} <b>确定性映射</b>：
 * 同一个修饰符 id 每次算出同一个 UUID，撤销才能只删自己那条。这也是为什么不能用随机 UUID。
 */
public final class PlayerAbilities {

    private PlayerAbilities() {
    }

    /** 属性 id 认不认得（认不得就别落地）。 */
    public static boolean available(ServerPlayer player, ResourceLocation attributeId) {
        Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(attributeId);
        return attribute != null && player.getAttribute(attribute) != null;
    }

    /** 加/更新一条瞬时修饰符。 */
    public static boolean grant(ServerPlayer player, ResourceLocation attributeId,
                                ResourceLocation modifierId, double amount, int operation) {
        Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(attributeId);
        if (attribute == null) return false;
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) return false;
        UUID uuid = uuidOf(modifierId);
        instance.removeModifier(uuid);   // 旧的那条先摘掉，再按新值装上（addOrUpdate 的等价写法）
        instance.addTransientModifier(
                new AttributeModifier(uuid, modifierId.toString(), amount, op(operation)));
        return true;
    }

    /** 撤销自己那条。返回是否真的删掉了一条。 */
    public static boolean revoke(ServerPlayer player, ResourceLocation attributeId, ResourceLocation modifierId) {
        Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(attributeId);
        if (attribute == null) return false;
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) return false;
        UUID uuid = uuidOf(modifierId);
        // 1.20.1 的 removeModifier(UUID) 返回 void：先查有没有，再删
        if (instance.getModifier(uuid) == null) return false;
        instance.removeModifier(uuid);
        return true;
    }

    private static UUID uuidOf(ResourceLocation modifierId) {
        return UUID.nameUUIDFromBytes(modifierId.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 线上序号：0 ADD_VALUE / 1 ADD_MULTIPLIED_BASE / 2 ADD_MULTIPLIED_TOTAL。 */
    private static AttributeModifier.Operation op(int operation) {
        return switch (operation) {
            case 1 -> AttributeModifier.Operation.MULTIPLY_BASE;
            case 2 -> AttributeModifier.Operation.MULTIPLY_TOTAL;
            default -> AttributeModifier.Operation.ADDITION;
        };
    }
}
