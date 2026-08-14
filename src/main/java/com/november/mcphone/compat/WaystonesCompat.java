package com.november.mcphone.compat;

import com.november.mcphone.MCphone;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.waystones.api.TeleportFlags;
import net.blay09.mods.waystones.menu.WaystoneSelectionListBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

import java.util.Set;

/**
 * Waystones（传送石碑）兼容层 —— 让手机里的「传送石」App 打开它的选点界面。
 *
 * ============================================================
 * 我们只做一件事：把对方的菜单端上来
 * ============================================================
 *
 * 选点列表、排序、搜索、分组、维度判定、经验扣除、传送本身、粒子与音效，
 * 全是 Waystones 的。我们不画界面、不注册菜单类型、不碰传送逻辑，只是在
 * 玩家点手机图标时，替他做一次它自己也会做的调用。
 *
 * 参照的是对方 WarpStoneItem.finishUsingItem 与 InventoryButtonMessage
 * ——两处的形状完全一样，都是"拼一个 MenuProvider，交给 Balm 打开"。
 *
 * ============================================================
 * 为什么不直接转发对方的 InventoryButtonMessage
 * ============================================================
 *
 * 那样确实更省事，但它的处理函数第一行就是：
 *
 *     if (!inventoryButtonMode.isEnabled()) return;
 *
 * 而这个配置项（inventoryButton）默认是空字符串，等同于关闭。转发它的话，
 * 我们的 App 在绝大多数服务器上是个按了没反应的死按钮，而且不报错、
 * 不留日志，排查起来毫无头绪。
 *
 * 那个配置管的是"要不要在背包界面里画那个按钮"，不是"允不允许这种传送"。
 * 我们有自己的入口，就不该被它的显示开关挡住。
 *
 * ============================================================
 * 为什么带 INVENTORY_BUTTON 这个标记
 * ============================================================
 *
 * 它决定玩家要付出什么。Waystones 默认的 warpRequirements 里有这么一条：
 *
 *     [source_is_inventory_button] add_cooldown(inventory_button, 300)
 *
 * 也就是说，对方认为"从界面里随时能传送"比"掏出一块石头"更强，所以额外
 * 压了 5 分钟冷却。手机 App 正是前者，带上这个标记等于自觉接受它为这类
 * 入口定的价——经验按距离扣（跨维度 27 级封顶）之外，再加冷却。
 *
 * 好处是我们一个平衡参数都不用发明：服主改 warpRequirements 就能调，
 * 改法与他调整背包按钮时完全一致，不需要另外学一套 MCphone 的配置。
 *
 * ============================================================
 * 类型隔离的规矩，和 CuriosCompat 一模一样
 * ============================================================
 *
 * Waystones 是可选依赖，编译期有类、运行期可能一个都没有。JVM 准备执行
 * 一个方法时会解析它引用到的类型，碰上不存在的类当场抛
 * NoClassDefFoundError——所以"装没装"的判断和"真去调它"必须分在两个方法
 * 里。写在同一个方法里的话，那句 if 还没轮到执行，方法本身就炸了。
 *
 * 别把 {@link #openSelection} 和 {@link #openSelectionInternal} 并回去。
 *
 * ============================================================
 * 这个类会被服务端加载，所以一个客户端类都不许出现
 * ============================================================
 *
 * 调用它的是网络包处理函数，那是两端共用的代码。上面用到的 Balm 与
 * Waystones 的类都是两端安全的（对方自己的物品与网络层也在服务端用它们），
 * 原版类只用到 ServerPlayer 与 Component。
 *
 * 往这里加东西时守住这条线：任何 net.minecraft.client.* 或
 * com.mojang.blaze3d.* 出现在这里，专用服务器会启动即崩，而崩溃信息不会
 * 提到传送石。这正是 1.0.45 修过的那种坑。
 */
public final class WaystonesCompat {

    private WaystonesCompat() {}

    private static final String WAYSTONES_MODID = "waystones";

    /**
     * 装没装 Waystones。
     *
     * 不缓存，与 CuriosCompat 同理：ModList 内部就是一次 map 查找，而缓存
     * 要挑一个"模组列表已经就绪"的时机去填，反而容易在加载早期取到错的值。
     */
    public static boolean isLoaded() {
        return ModList.get().isLoaded(WAYSTONES_MODID);
    }

    /**
     * 给玩家打开传送石选点界面。
     *
     * @return true 表示菜单已交给对方打开；没装 Waystones、或对方的 API
     *         变了导致调用失败时返回 false，调用方据此决定要不要提示玩家
     */
    public static boolean openSelection(ServerPlayer player) {
        if (!isLoaded()) return false;

        // 兜 Throwable 而不是 Exception：这里最可能的翻车方式是对方改了
        // 类名或方法签名，那抛出来的是 NoClassDefFoundError / NoSuchMethodError
        // ——都属于 Error，用 Exception 接不住。
        //
        // 兜住的代价是玩家点了没反应；不兜的代价是整个网络包处理函数抛异常，
        // 而那条路径上的异常会被 NeoForge 当成协议错误直接把玩家踢下线。
        try {
            openSelectionInternal(player);
            return true;
        } catch (Throwable t) {
            MCphone.LOGGER.error("打开传送石选点界面失败（Waystones 版本可能不兼容）", t);
            return false;
        }
    }

    /**
     * 真正碰 Waystones 的地方。
     *
     * 单独一个方法，只在上面确认装了之后才会被调到——理由见类注释，
     * 别把它并回去。
     */
    private static void openSelectionInternal(ServerPlayer player) {
        // withInventoryButtonTargets：玩家已激活的传送点 + 双生羽的目标。
        // 刻意不用 withTargetsForItem——那个额外含"返回传送门"，是给手持
        // 传送石的场景准备的，手机里没有那块石头，列进来只会让人困惑。
        //
        // 标题直接用对方的翻译键：玩家看到的界面就是 Waystones 的界面，
        // 顶上却写着 MCphone 的字样反而割裂，何况那样还得我们自己翻两份。
        var menuProvider = new WaystoneSelectionListBuilder(player)
                .withInventoryButtonTargets()
                .withFlags(Set.of(TeleportFlags.INVENTORY_BUTTON))
                .buildMenuProvider(
                        net.blay09.mods.waystones.menu.ModMenus.inventorySelection.get(),
                        Component.translatable("container.waystones.waystone_selection"));

        Balm.networking().openMenu(player, menuProvider);
    }
}
