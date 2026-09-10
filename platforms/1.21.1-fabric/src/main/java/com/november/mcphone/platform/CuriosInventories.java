package com.november.mcphone.platform;

import net.minecraft.world.entity.LivingEntity;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

import java.util.Optional;

/**
 * 拿到某个实体的饰品栏 —— 全仓唯一碰 {@link CuriosApi#getCuriosInventory} 的地方。
 *
 * <h2>为什么值得一层</h2>
 *
 * 两个版本的 Curios 在这一处返回的东西不同：这一支（Curios 9.x）直接给 {@code Optional}，另一支给的是 Forge 的 {@code LazyOptional}。
 * 方法名与后面那一串 {@code map / flatMap / ifPresent} 全都一样，
 * <b>差的只是中间那一步</b> —— 而 {@code CuriosCompat} 四个 {@code *Internal} 方法
 * 每一个都从这里开始，整份文件因此被挡在 {@code shared/} 之外。
 *
 * 收进来之后那四个方法体两支逐字相同，文件回到了 {@code shared/}。
 *
 * <h2>调用方仍然要先判在不在场</h2>
 *
 * 这个类<b>不判 Curios 装没装</b>。真调用必须关在 {@code CuriosCompat} 那几个
 * {@code *Internal} 方法里，由外层的 {@code ModPresence.isLoaded} 挡着 ——
 * 没装 Curios 时连这个类都不该被加载，否则 JVM 校验方法时就会抛
 * {@code NoClassDefFoundError}，轮不到那句 if。
 */
public final class CuriosInventories {

    private CuriosInventories() {}

    /** 某个实体的饰品栏；没有则空。<b>调用前必须已经确认 Curios 在场。</b> */
    public static Optional<ICuriosItemHandler> of(LivingEntity entity) {
        // 9.x 直接就是 Optional
        return CuriosApi.getCuriosInventory(entity);
    }
}
