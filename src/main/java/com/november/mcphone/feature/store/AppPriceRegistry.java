package com.november.mcphone.feature.store;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.cost.IAppPriceProvider;
import com.november.mcphone.api.cost.ICost;
import com.november.mcphone.util.SpiLoader;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * App 价格表 —— "下载这个 App 要花什么"的唯一权威。
 *
 * 为什么单独一个包，不放进 com.november.mcphone.store
 *
 * 因为那个包【事实上是客户端专用的】——AppSourceRegistry 与 LocalAppSource
 * 都引用了 api.client 与 gui 下的类，只是包名没体现出来。
 *
 * 而价格表服务端必须读得到：玩家点"购买"时，真正核对与扣除都发生在服务端，
 * 客户端报过来的价格一个字都不能信。把它放进那个包，专用服务器一加载就会
 * 顺着 AppSourceRegistry 摸到客户端类，启动即崩——正是 1.0.45 修过的那种坑。
 *
 * 所以另开一个包，包名之外还有这条注释顶着。往这个包里加东西时守住同一条
 * 线：不许出现任何 net.minecraft.client.* 或 com.mojang.blaze3d.*。
 *
 * 没报价 = 免费
 *
 * 查不到的 App 一律返回 {@link ICost#FREE}，不返回 null。调用方（界面与
 * 购买流程）因此不必到处判空，"免费"与"没定价"在语义上本来就是一回事。
 */
public final class AppPriceRegistry {

    private AppPriceRegistry() {}

    private static final Map<ResourceLocation, ICost> PRICES = new LinkedHashMap<>();
    private static boolean loaded = false;

    /**
     * 查一个 App 的价格。
     *
     * @return 永不为 null；没被报价过的返回 {@link ICost#FREE}
     */
    public static ICost priceOf(ResourceLocation appId) {
        ensureLoaded();
        if (appId == null) return ICost.FREE;
        return PRICES.getOrDefault(appId, ICost.FREE);
    }

    /** 这个 App 要花东西吗。免费的不必走购买流程，详情页直接显示"下载" */
    public static boolean isPaid(ResourceLocation appId) {
        return priceOf(appId) != ICost.FREE;
    }

    /**
     * 登记一条报价。
     *
     * 同 id 冲突时保留先登记的并告警。静默覆盖会让两边作者都查不出价格
     * 为什么不对——与 App 目录、应用来源两处的处置一致。
     *
     * @return true 表示登记成功
     */
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

    /**
     * 首次查询时扫描一次 SPI。
     *
     * 刻意做成惰性而不是在模组构造时扫：报价里按注册名查别的模组的物品是
     * 推荐写法（见 {@link IAppPriceProvider} 的文档），而模组构造那会儿
     * 物品注册表还没填完，那种写法会全部取到空气。等到第一次真有人问价格，
     * 注册表早就冻结了。
     */
    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        int providers = 0;
        int registered = 0;
        // 走 SpiLoader：下面那个 try 兜的是 prices() 抛异常，兜不住"这个类根本造不出来"——
        // 后者是从迭代器抛的，会中断整个扫描，所有报价一起丢，付费 App 全变免费
        for (IAppPriceProvider provider : SpiLoader.loadSafely(IAppPriceProvider.class, "App 报价")) {
            providers++;
            try {
                Map<ResourceLocation, ICost> prices = provider.prices();
                if (prices == null) continue;
                for (Map.Entry<ResourceLocation, ICost> e : prices.entrySet()) {
                    if (register(e.getKey(), e.getValue())) registered++;
                }
            } catch (Throwable t) {
                // 兜住单个报价方的翻车，其余照常。捕 Throwable 而不是
                // Exception：报价类最常见的死法是引用了没装的模组里的类，
                // 那抛的是 NoClassDefFoundError，属于 Error。
                MCphone.LOGGER.error("[MCphone] 报价方 {} 出错，已跳过",
                        provider.getClass().getName(), t);
            }
        }
        MCphone.LOGGER.info("[MCphone] 价格表就绪：{} 个报价方，共 {} 条报价",
                providers, registered);
    }
}
