package com.november.mcphone.core;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.november.mcphone.MCphone;
import com.november.mcphone.feature.settings.WallpaperData;

/**
 * MCphone 的数据组件注册。
 *
 * ================================================================
 * 为什么设备名要存在物品堆上
 * ================================================================
 *
 * 壁纸存在玩家身上（WallpaperData 附件），因为壁纸是"这个玩家的偏好"。
 * 设备名不同，它是"这一只手机的名字"：玩家可以有好几只手机，
 * 名字得跟着物品走，丢在地上、放进箱子、交易给别人都还在。
 * 所以用数据组件挂在 ItemStack 上，而不是玩家附件。
 *
 * 1.21 起 NBT 标签已被数据组件取代，自定义组件必须注册到
 * Registries.DATA_COMPONENT_TYPE。
 *
 * 两个编解码器缺一不可：
 *   - persistent    存档用，决定写进存档与物品 NBT 的样子
 *   - networkSynchronized  同步用，服务端改完要发给客户端才能显示出来
 * 只给 persistent 的话，多人游戏里改了名客户端看不见。
 */
public final class ModDataComponents {

    private ModDataComponents() {}

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, MCphone.MODID);

    /**
     * 设备名称。未命名的手机不带这个组件，而不是带一个空串——
     * 这样"没起过名"与"起了个空名"不会混淆，
     * 物品比较（如合并堆叠、配方匹配）也不会被空组件干扰。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> DEVICE_NAME =
            COMPONENTS.register("device_name",
                    () -> DataComponentType.<String>builder()
                            .persistent(Codec.STRING)
                            .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                            .build());

}
