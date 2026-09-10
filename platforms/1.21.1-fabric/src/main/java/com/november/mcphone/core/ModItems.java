package com.november.mcphone.core;

import com.november.mcphone.MCphone;
import com.november.mcphone.platform.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

/**
 * 物品注册。Fabric 没有 DeferredRegister，注册即构造：
 * {@link Registry#register} 返回的就是注册表里的那个实例。
 *
 * <b>持有者放在这个类里而不是 MCphone 上</b>，与 NeoForge 那一支在同一个全限定名
 * 下暴露 PHONE —— 用到它的地方（PhoneItemProperties、ModCreativeTabs）于是逐字相同。
 * 类型是 {@link Holder} 而不是裸 {@code Item}：共用侧写的是 {@code .get()}
 * （NeoForge 那边是 DeferredHolder），理由见 Holder 的类注释。
 */
public final class ModItems {

    private ModItems() {}

    /** 手机本体。一次只拿得动一部——它是一件设备，不是一摞消耗品 */
    public static final Holder<Item> PHONE = Holder.of(Registry.register(BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "phone"),
            new PhoneItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE), DeviceKind.PHONE)));

    /**
     * 平板 —— 手机外面包一圈铁锭合成，玩法上就是屏幕更大的那一台。
     *
     * 与手机共用 {@link PhoneItem}：App、聊天、终端卡槽全都认"是不是本模组的设备"
     * （{@link PhoneItem#isDevice}），加这一件不需要去改那些判断。两者只差物品模型与
     * 屏幕尺寸，后者由 {@link DeviceKind} 一路带到界面，见 {@code DeviceMetrics}。
     */
    public static final Holder<Item> TABLET = Holder.of(Registry.register(BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "tablet"),
            new PhoneItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE), DeviceKind.TABLET)));
}
