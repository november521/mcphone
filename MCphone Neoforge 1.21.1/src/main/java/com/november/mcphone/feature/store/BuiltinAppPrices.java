package com.november.mcphone.feature.store;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.cost.IAppPriceProvider;
import com.november.mcphone.api.cost.ICost;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内建 App 的报价：App 卖它替代的那个实物，其余 App 免费。传送石按注册名查、
 * 不做编译期依赖：查不到就不登记，此时 App 侧也不可用，两边正好对上。
 */
public final class BuiltinAppPrices implements IAppPriceProvider {

    /** 与 WaystonesCompat 里那处保持同一个注册名 */
    private static final ResourceLocation WARP_STONE_ITEM =
            ResourceLocation.fromNamespaceAndPath("waystones", "warp_stone");

    private static ResourceLocation app(String path) {
        return ResourceLocation.fromNamespaceAndPath(MCphone.MODID, path);
    }

    @Override
    public Map<ResourceLocation, ICost> prices() {
        Map<ResourceLocation, ICost> out = new LinkedHashMap<>();

        out.put(app("ender_chest"), ICost.of(Items.ENDER_CHEST, 1));

        Item warpStone = BuiltInRegistries.ITEM.get(WARP_STONE_ITEM);
        if (warpStone != Items.AIR) {
            out.put(app("waystone"), ICost.of(warpStone, 1));
        }

        return out;
    }
}
