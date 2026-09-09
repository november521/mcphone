package com.november.mcphone.core.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.PhoneItemData;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;

/**
 * 手上那部手机亮不亮 —— 物品模型按这个数在黑屏与白屏之间切。
 *
 * 界面开着的时候屏幕是亮的，这在物品上也该看得见：{@code models/item/phone.json} 挂着一条
 * override，{@code mcphone:screen_on} 到 1 就换成 {@code item/phone_white}（白屏那份模型），
 * 否则用父模型 {@code item/phone_black}。两份模型的几何完全一样，只差贴图里屏幕那一块。
 *
 * 亮不亮读的是<b>物品堆上的组件</b>，不是本机的界面状态
 *
 * 这是为了让【别人】也看得见：物品属性函数是在每个客户端上跑的，它渲染的可能是另一个玩家
 * 手里那部手机，而那个玩家开没开手机，本机无从知道。所以这条链是：本机把自己的状态报给
 * 服务端（{@link PhoneScreenOnSync}）→ 服务端写进 {@code PhoneItemData.setScreenOn}
 * → 组件随物品同步给所有看得见这只手的人 → 这里读它。
 *
 * 于是地上掉的、箱子里的、别人身上没开着的那部，都不带这个组件，一律黑屏。
 */
public final class PhoneItemProperties {

    private PhoneItemProperties() {}

    /** 模型 override 里写的那个键：{@code "mcphone:screen_on": 1} */
    public static final ResourceLocation SCREEN_ON =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "screen_on");

    /**
     * 由 {@code MCphoneClient.onClientSetup} 在 enqueueWork 里调。
     *
     * 必须排到主线程：{@code ItemProperties} 后面是一张普通 HashMap，而客户端 setup 是和
     * 别的模组并行跑的。
     */
    public static void register() {
        ItemProperties.register(MCphone.PHONE.get(), SCREEN_ON,
                (stack, level, entity, seed) -> PhoneItemData.isScreenOn(stack) ? 1f : 0f);
    }
}
