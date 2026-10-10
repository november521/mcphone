package com.november.mcphone.platform;

import net.neoforged.fml.ModList;

/**
 * 「这个模组装了没有」，以及「它叫什么名字」—— 全仓唯一碰 {@link ModList} 的地方。
 *
 * <h2>为什么值得单独一层</h2>
 *
 * 判断某个模组在不在场，是这个仓库里最常做的一件事：十七个文件、二十来处，
 * 每一处联动都从这一句开始。而这一句恰好是<b>加载器专有</b>的：
 * NeoForge 上是 {@code net.neoforged.fml.ModList}，Forge 1.20.1 上是
 * {@code net.minecraftforge.fml.ModList} —— 方法名与语义一模一样，只差包名。
 *
 * 收进这里之后，两支之间要改的就只剩这个文件里的一行 import。
 *
 * <b>收益到此为止，别把它说大。</b>那十七个文件里，有十四个从此在两个加载器上
 * 完全同构；剩下三个照样要分叉，因为它们身上还带着别的加载器专有类型：
 *
 * <ul>
 *   <li>{@code compat/CompatModule} —— {@code net.neoforged.bus.api.IEventBus}
 *   <li>{@code feature/settings/client/AppManagerDetail} ——
 *       {@code net.neoforged.neoforge.client.settings.KeyModifier}
 *   <li>{@code feature/terminal/integration/Terminals} ——
 *       {@code net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent}
 * </ul>
 *
 * 这三样各自属于事件总线、按键修饰符、生命周期事件那几层，要等它们各自的门面。
 *
 * <h2>这个包是干什么的</h2>
 *
 * {@code platform} 下装的是「两个加载器上做同一件事、但写法不同」的东西。
 * 眼下只有这一个；把 1.20.1 那一支并回来时，网络包注册、玩家附着数据、
 * 物品数据这几样也会挪进来。<b>不要往这里放业务逻辑</b> —— 判断"装了 Waystones
 * 就多一个 App"是业务，属于 {@code compat}；"怎么问加载器要模组列表"才是这里的事。
 *
 * <h2>不做缓存 —— 省的不是那次查表，是那个时机</h2>
 *
 * {@code isLoaded} 本身就是一次 map 查表，模组列表在游戏跑起来之后也不会再变，
 * 缓存换不来多少东西。真正的理由在另一头：<b>缓存得挑一个「模组列表已经就绪」的
 * 时机去填</b>，而那个时机不好挑 —— {@code ModList.get()} 在 FML 把它装好之前
 * 返回的是 null，填早了不是拿到错的值就是当场 NPE，而且只在加载早期发作。
 *
 * 所以这里每次都现问。调用方也不要自己缓存：
 * {@code CuriosCompat} / {@code WaystonesCompat} 的 {@code isLoaded()} 都是照这条写的。
 */
public final class ModPresence {

    private ModPresence() {}

    /**
     * 这个模组装了没有。
     *
     * @param modId 对方 mods.toml 里的那个 modid，不是显示名
     */
    public static boolean isLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    /**
     * 一个模组的显示名，查不到就退回 modid。
     *
     * 从模组列表现拿而不是写死：这种名字对方自己会改，有的模组还会本地化它。
     * 查不到时退回 modid 而不是空串 —— 总比空着强，至少玩家还认得出是哪一个。
     */
    public static String displayName(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(modId);
    }
}
