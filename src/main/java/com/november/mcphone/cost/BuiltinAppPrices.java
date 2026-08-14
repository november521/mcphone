package com.november.mcphone.cost;

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
 * MCphone 内建 App 的报价。
 *
 * ============================================================
 * 定价的思路：App 卖它替代的那个实物
 * ============================================================
 *
 * 末影箱 App 收一个末影箱，传送石 App 收一块传送石。玩家付出的正是那件
 * 他不必再随身携带的东西，这条规则不需要解释也不需要平衡表——它自己就说
 * 得通，而且随便哪个整合包的进度曲线都自动适配：末影箱难做的包里 App 就贵。
 *
 * 其余 App（相机、相册、音乐、聊天、记事本）不在这里，也就是免费。它们
 * 没有对应的实物，硬安一个价格只会变成凭空定的数字。
 *
 * ============================================================
 * 传送石那条为什么要按注册名查
 * ============================================================
 *
 * waystones:warp_stone 只在装了传送石碑时才存在。直接写它的类要求编译期
 * 依赖，而按注册名查不需要——查不到就不登记这一条。
 *
 * 查不到时那个 App 本身也不会存在（WaystoneApp.isAvailable 会返回 false），
 * 两边正好对上，玩家不会在商店里看到一个标价"1 × 空气"的东西。
 */
public final class BuiltinAppPrices implements IAppPriceProvider {

    /** waystones:warp_stone —— 与 WaystonesCompat 里那处保持同一个来源认知 */
    private static final ResourceLocation WARP_STONE_ITEM =
            ResourceLocation.fromNamespaceAndPath("waystones", "warp_stone");

    private static ResourceLocation app(String path) {
        return ResourceLocation.fromNamespaceAndPath(MCphone.MODID, path);
    }

    @Override
    public Map<ResourceLocation, ICost> prices() {
        Map<ResourceLocation, ICost> out = new LinkedHashMap<>();

        // 便携末影箱：收一个末影箱。原版物品，一定在。
        out.put(app("ender_chest"), ICost.of(Items.ENDER_CHEST, 1));

        // 传送石：收一块传送石。没装传送石碑就没有这条。
        Item warpStone = BuiltInRegistries.ITEM.get(WARP_STONE_ITEM);
        if (warpStone != Items.AIR) {
            out.put(app("waystone"), ICost.of(warpStone, 1));
        }

        return out;
    }
}
