package com.november.mcphone.platform;

import com.november.mcphone.api.sdk.resources.ResourceReading;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler;
import java.math.BigInteger;

/** Forge 能力的只读门面；缺少能力直接返回 null。 */
public final class ResourcePlatform {
    private ResourcePlatform(){}
    public static String fluidUnit(){return "mB";}
    public static boolean supported(String provider){return provider.equals("forge_energy")||provider.equals("forge_fluid");}
    public static ResourceReading readItem(String provider,ItemStack stack){
        if(provider.equals("forge_energy"))return energy(stack.getCapability(ForgeCapabilities.ENERGY).orElse(null));
        if(provider.equals("forge_fluid"))return fluid(stack.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).orElse(null));return null;
    }
    public static ResourceReading readBlock(String provider,ServerLevel level,BlockPos pos,Direction side){var entity=level.getBlockEntity(pos);if(entity==null)return null;
        if(provider.equals("forge_energy"))return energy(entity.getCapability(ForgeCapabilities.ENERGY,side).orElse(null));
        if(provider.equals("forge_fluid"))return fluid(entity.getCapability(ForgeCapabilities.FLUID_HANDLER,side).orElse(null));return null;
    }
    private static ResourceReading energy(IEnergyStorage cap){return cap==null?null:new ResourceReading(BigInteger.valueOf(cap.getEnergyStored()),BigInteger.valueOf(cap.getMaxEnergyStored()),cap.canExtract(),cap.canReceive());}
    private static ResourceReading fluid(IFluidHandler cap){if(cap==null)return null;int tanks=cap.getTanks();if(tanks<0||tanks>64)throw new IllegalArgumentException("储罐数量超额");BigInteger stored=BigInteger.ZERO,capacity=BigInteger.ZERO;
        for(int i=0;i<tanks;i++){int amount=cap.getFluidInTank(i).getAmount(),max=cap.getTankCapacity(i);if(amount<0||max<amount)throw new IllegalArgumentException("流体读数异常");stored=stored.add(BigInteger.valueOf(amount));capacity=capacity.add(BigInteger.valueOf(max));}
        return new ResourceReading(stored,capacity,false,false);
    }
}
