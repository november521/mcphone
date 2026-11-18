package com.november.mcphone.core.client;
import com.mojang.serialization.MapCodec;
import com.november.mcphone.core.PhoneItemData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperty;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RegisterConditionalItemModelPropertyEvent;
/** 新物品模型系统使用条件属性，继续读取同步到物品堆的屏幕状态。 */
public final class PhoneItemProperties {
    public static final Identifier SCREEN_ON=Identifier.fromNamespaceAndPath("mcphone","screen_on");
    private PhoneItemProperties() {}
    public static void register(RegisterConditionalItemModelPropertyEvent event) { event.register(SCREEN_ON,ScreenOn.CODEC); }
    public record ScreenOn() implements ConditionalItemModelProperty {
        public static final MapCodec<ScreenOn> CODEC=MapCodec.unit(new ScreenOn());
        @Override public MapCodec<ScreenOn> type() { return CODEC; }
        @Override public boolean get(ItemStack stack,ClientLevel level,LivingEntity entity,int seed,ItemDisplayContext context) {
            return PhoneItemData.isScreenOn(stack);
        }
    }
}
