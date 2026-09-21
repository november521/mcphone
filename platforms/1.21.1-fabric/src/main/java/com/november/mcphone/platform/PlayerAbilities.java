package com.november.mcphone.platform;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * 属性修饰符的授予与撤销（施工方案 §18.4）。<b>1.21.1 这一支</b>。
 *
 * <p>只做两件这里独有的翻译：{@code ResourceLocation} 的属性 id → {@code Holder<Attribute>}，
 * 以及我们线上用的 operation 序号 → 这一支的枚举名。业务（该不该给、给多少）在外面。
 *
 * <p><b>只加/删自己那条</b>：修饰符 id 是确定的（{@code mcphone:script/<app>/<cap>}），
 * 撤销只删这个 id —— 别的模组与创造模式授予的修饰符一个都不碰。修饰符是
 * {@code addOrUpdateTransientModifier}：瞬时的，不写存档，重登即失效（§18.4）。
 */
public final class PlayerAbilities {

    private PlayerAbilities() {
    }

    /** 属性 id 认不认得（数据包模组可能换了属性表；认不得就别落地）。 */
    public static boolean available(ServerPlayer player, ResourceLocation attributeId) {
        Holder<Attribute> attribute = attr(attributeId);
        return attribute != null && player.getAttribute(attribute) != null;
    }

    /** 加/更新一条瞬时修饰符。 */
    public static boolean grant(ServerPlayer player, ResourceLocation attributeId,
                                ResourceLocation modifierId, double amount, int operation) {
        Holder<Attribute> attribute = attr(attributeId);
        if (attribute == null) return false;
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) return false;
        instance.addOrUpdateTransientModifier(
                new AttributeModifier(modifierId, amount, op(operation)));
        return true;
    }

    /** 撤销自己那条。返回是否真的删掉了一条。 */
    public static boolean revoke(ServerPlayer player, ResourceLocation attributeId, ResourceLocation modifierId) {
        Holder<Attribute> attribute = attr(attributeId);
        if (attribute == null) return false;
        AttributeInstance instance = player.getAttribute(attribute);
        return instance != null && instance.removeModifier(modifierId);
    }

    private static Holder<Attribute> attr(ResourceLocation id) {
        return BuiltInRegistries.ATTRIBUTE.getHolder(id).orElse(null);
    }

    /** 线上序号：0 ADD_VALUE / 1 ADD_MULTIPLIED_BASE / 2 ADD_MULTIPLIED_TOTAL。 */
    private static AttributeModifier.Operation op(int operation) {
        return switch (operation) {
            case 1 -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case 2 -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> AttributeModifier.Operation.ADD_VALUE;
        };
    }
}
