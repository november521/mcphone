package com.november.mcphone.core.menu;

import com.november.mcphone.MCphone;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 菜单类型注册。
 *
 * 每个需要格子的 App 在这里注册一个 MenuType；菜单实现共用
 * {@link PhoneContainerMenu}，区别只在容器来源与格数。
 *
 * 注册在 MCphone 构造函数中挂到模组总线。
 */
public final class ModMenus {

    private ModMenus() {}

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MCphone.MODID);

    /** 末影箱容量，与原版一致（3 行 ×9），在手机里按 6 列排 */
    public static final int ENDER_CHEST_SIZE = 27;

    /**
     * 便携末影箱。
     *
     * 这里的工厂是【客户端】用的：服务端 openMenu 时自己 new 好菜单，
     * 客户端只收到菜单类型与 id，靠这个工厂重建一个等大的空壳，
     * 内容随后由原版的容器同步包填入。
     */
    public static final Supplier<MenuType<PhoneContainerMenu>> ENDER_CHEST =
            MENUS.register("ender_chest", () -> new MenuType<>(
                    (containerId, playerInventory) -> new PhoneContainerMenu(
                            ModMenus.ENDER_CHEST.get(), containerId, playerInventory, ENDER_CHEST_SIZE),
                    FeatureFlags.DEFAULT_FLAGS));
}
