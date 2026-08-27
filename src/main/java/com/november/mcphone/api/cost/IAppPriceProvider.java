package com.november.mcphone.api.cost;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * App 报价 —— 声明"下载某个 App 要花什么"。
 *
 * 通过 Java SPI 注册，文件名 META-INF/services/com.november.mcphone.api.cost.IAppPriceProvider。
 * 没被任何人报价的 App 就是免费的，不需要显式声明 {@link ICost#FREE}。
 *
 * 价格两端都要用（客户端画价、服务端扣物），所以实现类里【不许】出现任何客户端
 * 类型——碰了的话专用服务器会在扫描 SPI 时崩，而崩溃信息不会提到你的报价类。
 *
 * 只在第一次有人查价格时扫描一次，那时注册表已经就绪，可以按注册名查别的模组
 * 的物品，查不到就别登记这一条。同一个 App id 被多方报价时先注册者生效，后来
 * 者被忽略并告警。
 */
public interface IAppPriceProvider {

    /** @return App id → 代价。返回空表代表"我不给任何 App 报价"，不是错误 */
    Map<ResourceLocation, ICost> prices();
}
