package com.november.mcphone.api.cost;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * App 报价 —— 声明"下载某个 App 要花什么"。
 *
 * 【附属模组开发者指南】
 *
 * 通过 Java SPI 注册，与 App 本身的发现机制一致：
 *
 *   文件名: META-INF/services/com.november.mcphone.api.cost.IAppPriceProvider
 *   内容:   com.yourmod.MyAppPrices
 *
 * 实现只要返回一张表：
 *
 *   public final class MyAppPrices implements IAppPriceProvider {
 *       &#64;Override
 *       public Map&lt;ResourceLocation, ICost&gt; prices() {
 *           return Map.of(
 *               ResourceLocation.fromNamespaceAndPath("mymod", "calculator"),
 *               ICost.of(Items.REDSTONE, 4));
 *       }
 *   }
 *
 * 没被任何人报价的 App 就是免费的，不需要显式声明 {@link ICost#FREE}。
 *
 * 这个接口为什么【不】在 api.client 下
 *
 * 因为价格两端都要用：客户端拿它画详情页上的"要花什么"，服务端拿它在
 * 真正扣东西前核对。而 {@link com.november.mcphone.api.client.app.IPhoneApp}
 * 是客户端专用的（签名里有 GuiGraphics），服务端根本读不到它——所以价格
 * 不能挂在 App 上，必须单独走这条两端安全的路。
 *
 * 实现类里因此【不许】出现任何客户端类型。碰了的话，专用服务器会在扫描
 * SPI 时崩，而崩溃信息不会提到你的报价类。
 *
 * 报价里引用别的模组的物品
 *
 * 直接写 Items.XXX 要求编译期就有那个模组。想给"别人的物品"定价，按
 * 注册名查即可，查不到就别登记这一条：
 *
 *   Item item = BuiltInRegistries.ITEM.get(
 *           ResourceLocation.fromNamespaceAndPath("othermod", "gem"));
 *   if (item != Items.AIR) prices.put(myAppId, ICost.of(item, 1));
 *
 * 查不到通常意味着那个模组没装，而那时你的 App 多半也不该存在
 * （见 IPhoneApp#isAvailable）。登记一条"1 × 空气"只会让玩家困惑。
 *
 * 什么时候被调用
 *
 * 第一次有人查价格时扫描一次，此后不再调用。也就是说会在【注册表已经
 * 就绪之后】才发生，上面那种按 id 查物品的写法是安全的。
 *
 * 同一个 App id 被多方报价时，先注册的那个生效，后来者会被忽略并告警
 * ——静默覆盖会让两边作者都查不出价格为什么不对。
 */
public interface IAppPriceProvider {

    /**
     * 这一份报价单。
     *
     * @return App id → 代价。返回空表代表"我不给任何 App 报价"，不是错误
     */
    Map<ResourceLocation, ICost> prices();
}
