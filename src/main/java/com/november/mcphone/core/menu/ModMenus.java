package com.november.mcphone.core.menu;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.music.menu.DiscBayMenu;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

/**
 * 菜单类型注册。
 *
 * 每个需要格子的地方在这里注册一个 MenuType。多数共用
 * {@link PhoneContainerMenu}（区别只在容器来源与格数）；唱片仓是个例外，
 * 它只有一格、还要限制只收唱片，所以自带一个菜单实现。
 *
 * 注册在 MCphone.onInitialize 中调用。
 */
public final class ModMenus {

    private ModMenus() {}

    /** 末影箱容量，与原版一致（3 行 ×9），在手机里按 6 列排 */
    public static final int ENDER_CHEST_SIZE = 27;

    /**
     * 便携末影箱。
     *
     * 这里的工厂是【客户端】用的：服务端 openMenu 时自己 new 好菜单，
     * 客户端只收到菜单类型与 id，靠这个工厂重建一个等大的空壳，
     * 内容随后由原版的容器同步包填入。
     */
    public static final MenuType<PhoneContainerMenu> ENDER_CHEST = new MenuType<>(
            (containerId, playerInventory) -> new PhoneContainerMenu(
                    ModMenus.ENDER_CHEST, containerId, playerInventory, ENDER_CHEST_SIZE),
            FeatureFlags.DEFAULT_FLAGS);

    /**
     * 唱片仓 —— 一个唱片格 ＋ 玩家背包。
     *
     * 存在的理由是手机界面里没有背包：不开这个界面的话，玩家想把一张唱片
     * 放进手机就得先关手机、把唱片翻到主手、再开手机。见 {@link DiscBayMenu}。
     */
    public static final MenuType<DiscBayMenu> DISC_BAY = new MenuType<>(
            DiscBayMenu::new,
            FeatureFlags.DEFAULT_FLAGS);

    /** 由 MCphone.onInitialize 调用 */
    public static void register() {
        Registry.register(BuiltInRegistries.MENU,
                ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "ender_chest"),
                ENDER_CHEST);
        Registry.register(BuiltInRegistries.MENU,
                ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "disc_bay"),
                DISC_BAY);
    }
}
