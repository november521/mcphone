package com.november.mcphone.core;

import com.november.mcphone.MCphone;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 物品注册。
 *
 * 与 NeoForge 那一支的差别，移植时最容易踩的一处
 *
 *   Forge 1.20.1   DeferredRegister.create(ForgeRegistries.ITEMS, MODID) → RegistryObject&lt;T&gt;
 *   NeoForge 21.x  DeferredRegister.createItems(MODID)                   → DeferredHolder&lt;Item, T&gt;
 *
 * 名字像、语义也像，但类型不一样，`ForgeRegistries` 这个类在 NeoForge 上压根
 * 不存在。那边的 8 处 DeferredHolder 搬过来都要改成 RegistryObject。
 */
public final class ModItems {

    private ModItems() {}

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MCphone.MODID);

    /** 手机本体。一次只拿得动一部——它是一件设备，不是一摞消耗品 */
    public static final RegistryObject<Item> PHONE =
            ITEMS.register("phone", () -> new PhoneItem(new Item.Properties().stacksTo(1), DeviceKind.PHONE));

    /**
     * 平板 —— 手机外面包一圈铁锭合成，玩法上就是屏幕更大的那一台。
     *
     * 与手机共用 {@link PhoneItem}：App、聊天、终端卡槽全都认"是不是本模组的设备"
     * （{@link PhoneItem#isDevice}），加这一件不需要去改那些判断。两者只差物品模型与
     * 屏幕尺寸，后者由 {@link DeviceKind} 一路带到界面，见 {@code DeviceMetrics}。
     */
    public static final RegistryObject<Item> TABLET =
            ITEMS.register("tablet", () -> new PhoneItem(new Item.Properties().stacksTo(1), DeviceKind.TABLET));
}
