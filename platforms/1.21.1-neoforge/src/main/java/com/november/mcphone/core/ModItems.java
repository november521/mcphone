package com.november.mcphone.core;

import com.november.mcphone.MCphone;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 物品注册。
 *
 * 与 Forge 1.20.1 那一支的差别，移植时最容易踩的一处
 *
 *   Forge 1.20.1   DeferredRegister.create(ForgeRegistries.ITEMS, MODID) → RegistryObject&lt;T&gt;
 *   NeoForge 21.x  DeferredRegister.createItems(MODID)                   → DeferredHolder&lt;Item, T&gt;
 *
 * 名字像、语义也像，但类型不一样，{@code ForgeRegistries} 这个类在 NeoForge 上压根不存在。
 *
 * <b>持有者放在这个类里而不是 MCphone 上</b>，两支因此在同一个全限定名下暴露 PHONE ——
 * 用到它的地方（PhoneItemProperties、ModCreativeTabs）于是两支逐字相同。
 * 持有者的类型仍然不一样，但那被关在这个文件里，而这个文件本就是各平台各一份的注册入口。
 */
public final class ModItems {

    private ModItems() {}

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MCphone.MODID);

    /** 手机本体。一次只拿得动一部——它是一件设备，不是一摞消耗品 */
    public static final DeferredItem<PhoneItem> PHONE = ITEMS.registerItem("phone",
            props -> new PhoneItem(props.stacksTo(1).rarity(Rarity.RARE)));
}
