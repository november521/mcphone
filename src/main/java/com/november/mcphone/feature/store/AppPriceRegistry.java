package com.november.mcphone.feature.store;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.cost.IAppPriceProvider;
import com.november.mcphone.api.cost.ICost;
import com.november.mcphone.util.SpiLoader;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * App 价格表——"下载这个 App 要花什么"的唯一权威，服务端必须读得到。
 * 本包不许引用任何 net.minecraft.client.* / com.mojang.blaze3d.*，否则专用服务器启动即崩。
 * 没报价 = 免费：查不到一律返回 {@link ICost#FREE}，不返回 null。
 */
public final class AppPriceRegistry {

    private AppPriceRegistry() {}

    private static final Map<ResourceLocation, ICost> PRICES = new LinkedHashMap<>();
    private static boolean loaded = false;

    /** 永不为 null；没被报价过的返回 {@link ICost#FREE} */
    public static ICost priceOf(ResourceLocation appId) {
        ensureLoaded();
        if (appId == null) return ICost.FREE;
        return PRICES.getOrDefault(appId, ICost.FREE);
    }

    public static boolean isPaid(ResourceLocation appId) {
        return priceOf(appId) != ICost.FREE;
    }

    /** 同 id 冲突时保留先登记的并告警；返回是否登记成功 */
    public static boolean register(ResourceLocation appId, ICost cost) {
        if (appId == null || cost == null) {
            MCphone.LOGGER.warn("[MCphone] 报价登记失败: id 或代价为空");
            return false;
        }
        ICost old = PRICES.get(appId);
        if (old != null) {
            MCphone.LOGGER.warn("[MCphone] App '{}' 已有报价，忽略重复登记", appId);
            return false;
        }
        PRICES.put(appId, cost);
        return true;
    }

    /** 故意惰性扫描 SPI：模组构造时物品注册表还没填完，按注册名查会全部取到空气 */
    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        int providers = 0;
        int registered = 0;
        // 必须走 SpiLoader："类造不出来"从迭代器抛出，会中断整个扫描、付费 App 全变免费
        for (IAppPriceProvider provider : SpiLoader.loadSafely(IAppPriceProvider.class, "App 报价")) {
            providers++;
            try {
                Map<ResourceLocation, ICost> prices = provider.prices();
                if (prices == null) continue;
                for (Map.Entry<ResourceLocation, ICost> e : prices.entrySet()) {
                    if (register(e.getKey(), e.getValue())) registered++;
                }
            } catch (Throwable t) {
                // 捕 Throwable：引用没装模组的类抛的是 NoClassDefFoundError，属于 Error
                MCphone.LOGGER.error("[MCphone] 报价方 {} 出错，已跳过",
                        provider.getClass().getName(), t);
            }
        }
        MCphone.LOGGER.info("[MCphone] 价格表就绪：{} 个报价方，共 {} 条报价",
                providers, registered);
    }
}
