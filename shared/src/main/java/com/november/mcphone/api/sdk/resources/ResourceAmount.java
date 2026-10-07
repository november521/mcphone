package com.november.mcphone.api.sdk.resources;
import java.math.BigInteger;
import net.minecraft.resources.ResourceLocation;
public record ResourceAmount(ResourceLocation typeId,BigInteger amount) {
    public ResourceAmount {if(typeId==null)throw new IllegalArgumentException("资源类型缺失");ResourceReading.valid(amount);}
}
