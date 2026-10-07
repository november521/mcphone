package com.november.mcphone.platform;

import com.november.mcphone.api.sdk.resources.ResourceReading;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.fabricmc.fabric.api.transfer.v1.fluid.*;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import java.math.BigInteger;

/** Fabric 流体最小单位为 droplet；没有 Fabric 通用能源能力时不伪造可用能源提供者。 */
public final class ResourcePlatform {
    private ResourcePlatform(){}
    public static String fluidUnit(){return "droplet";}
    public static boolean supported(String provider){return provider.equals("forge_fluid");}
    public static ResourceReading readItem(String provider,ItemStack stack){return provider.equals("forge_fluid")?fluid(FluidStorage.ITEM.find(stack,ContainerItemContext.withConstant(stack))):null;}
    public static ResourceReading readBlock(String provider,ServerLevel level,BlockPos pos,Direction side){return provider.equals("forge_fluid")?fluid(FluidStorage.SIDED.find(level,pos,side)):null;}
    private static ResourceReading fluid(Storage<FluidVariant> storage){if(storage==null)return null;BigInteger stored=BigInteger.ZERO,capacity=BigInteger.ZERO;int tanks=0;
        for(var tank:storage){if(++tanks>64)throw new IllegalArgumentException("储罐数量超额");long amount=tank.getAmount(),max=tank.getCapacity();if(amount<0||max<amount)throw new IllegalArgumentException("流体读数异常");stored=stored.add(BigInteger.valueOf(amount));capacity=capacity.add(BigInteger.valueOf(max));}
        return new ResourceReading(stored,capacity,storage.supportsExtraction(),storage.supportsInsertion());
    }
}
