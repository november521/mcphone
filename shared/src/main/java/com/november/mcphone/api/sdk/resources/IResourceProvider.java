package com.november.mcphone.api.sdk.resources;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Java 模组的只读适配 SPI。化学品等没有通用标准时由适配模组实现；不可从脚本注册。 */
public interface IResourceProvider {
    ResourceType type();
    boolean isAvailable();
    default ResourceReading readBlock(ServerLevel level,BlockPos pos,Direction side){return null;}
    default ResourceReading readItem(ItemStack stack){return null;}
}
