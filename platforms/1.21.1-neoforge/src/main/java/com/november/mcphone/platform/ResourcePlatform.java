package com.november.mcphone.platform;

import com.november.mcphone.api.sdk.resources.ResourceReading;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.math.BigInteger;

/** NeoForge 通用能力只读门面；不调用提取、插入、填充或排空。 */
public final class ResourcePlatform {
    private ResourcePlatform(){}
    public static String fluidUnit(){return "mB";}
    public static boolean supported(String provider){return provider.equals("forge_energy")||provider.equals("forge_fluid");}
    public static ResourceReading readItem(String provider,ItemStack stack){
        if(provider.equals("forge_energy"))return energy(stack.getCapability(Capabilities.EnergyStorage.ITEM));
        if(provider.equals("forge_fluid"))return fluid(stack.getCapability(Capabilities.FluidHandler.ITEM));return null;
    }
    public static ResourceReading readBlock(String provider,ServerLevel level,BlockPos pos,Direction side){
        if(provider.equals("forge_energy"))return energy(level.getCapability(Capabilities.EnergyStorage.BLOCK,pos,side));
        if(provider.equals("forge_fluid"))return fluid(level.getCapability(Capabilities.FluidHandler.BLOCK,pos,side));return null;
    }
    private static ResourceReading energy(IEnergyStorage cap){return cap==null?null:new ResourceReading(BigInteger.valueOf(cap.getEnergyStored()),BigInteger.valueOf(cap.getMaxEnergyStored()),cap.canExtract(),cap.canReceive());}
    private static ResourceReading fluid(IFluidHandler cap){if(cap==null)return null;int tanks=cap.getTanks();if(tanks<0||tanks>64)throw new IllegalArgumentException("储罐数量超额");BigInteger stored=BigInteger.ZERO,capacity=BigInteger.ZERO;
        for(int i=0;i<tanks;i++){int amount=cap.getFluidInTank(i).getAmount(),max=cap.getTankCapacity(i);if(amount<0||max<amount)throw new IllegalArgumentException("流体读数异常");stored=stored.add(BigInteger.valueOf(amount));capacity=capacity.add(BigInteger.valueOf(max));}
        return new ResourceReading(stored,capacity,false,false);
    }
}
