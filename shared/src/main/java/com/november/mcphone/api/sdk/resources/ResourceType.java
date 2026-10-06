package com.november.mcphone.api.sdk.resources;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 单位只作显示；金额与容量始终采用整数最小单位。 */
public record ResourceType(ResourceLocation id,Kind kind,Component displayName,String unit,int decimals,ResourceLocation icon) {
    public enum Kind {ENERGY,FLUID,CHEMICAL,CUSTOM}
    public ResourceType {
        if(id==null||id.toString().length()>64||kind==null||displayName==null||unit==null||unit.length()>16||unit.chars().anyMatch(Character::isISOControl)||decimals<0||decimals>18)throw new IllegalArgumentException("资源元数据无效");
    }
}
