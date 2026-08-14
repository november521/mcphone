package com.november.mcphone.compat;

import com.november.mcphone.MCphone;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.waystones.menu.WaystoneSelectionListBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

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
 * 为什么把 warpItem 设成一块传送石
 * ============================================================
 *
 * 因为 Waystones 判断"这次传送算哪种来源"，靠的不是菜单类型，而是 warpItem：
 *
 *     registerConditionResolver("source_is_warp_stone",
 *             (context, parameters) -&gt; context.getWarpItem().is(ModItemTags.WARP_STONES));
 *
 * 服主写在 warpRequirements 里的规则几乎都带这类条件。warpItem 空着的话，
 * 他为传送石配的任何规则我们一条都不命中——冷却、代价、豁免全都对不上，
 * 而且是静默对不上，不报错，只是"配了没效果"。
 *
 * 1.1.4 曾经把这个 App 声明成 INVENTORY_BUTTON，那是错的。它会命中默认
 * 规则里的
 *
 *     [source_is_inventory_button] add_cooldown(inventory_button, 300)
 *
 * 于是 App 无端多出 5 分钟冷却，而传送石本身默认【没有】冷却——它付的是
 * 耐久。玩家按的是"传送石"，拿到的却是背包按钮的价，这不是平衡取舍，
 * 是归错了类。
 *
 * 现在设成传送石，服主怎么配传送石，这个 App 就怎么走。
 *
 * ============================================================
 * 但不扣耐久
 * ============================================================
 *
 * 扣耐久只发生在物品自己的 postTeleportHandler 里（见对方
 * WarpStoneItem.finishUsingItem），我们不注册那个回调就不会扣。warpItem
 * 在别处不会被消耗——全仓的 shrink/hurtAndBreak 只出现在背包代价解析器、
 * 墓碑与传送板，都与它无关。
 *
 * 后果得认：在"无冷却＋扣耐久"这类配置下，本 App 比真传送石更强——真石头
 * 128 次就碎，手机不会。这是刻意的取舍：手机里本来就没有那块石头，凭空
 * 拿玩家背包里另一块石头的耐久去抵，比不扣更难解释。
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
        // withTargetsForItem 内部会顺带 withWarpItem，所以这一句同时定下了
        // "去哪些点"和"按哪种来源计价"。目标 = 已激活的传送点 + 双生羽 +
        // 返回传送门，与手持传送石时一字不差。
        //
        // 不带任何 flag。flag 必须与菜单类型对齐：warpStoneSelection 在对方
        // 的 ModMenus 里是用 Set.of() 注册的，客户端重建菜单时用的就是那个
        // 空集合。这里多传一个 flag，服务端与客户端的菜单会带着不同的标记，
        // 是自找的不一致。
        //
        // 标题直接用对方的翻译键：玩家看到的界面就是 Waystones 的界面，
        // 顶上却写着 MCphone 的字样反而割裂，何况那样还得我们自己翻两份。
        var menuProvider = new WaystoneSelectionListBuilder(player)
                .withTargetsForItem(warpStoneStack())
                .buildMenuProvider(
                        net.blay09.mods.waystones.menu.ModMenus.warpStoneSelection.get(),
                        Component.translatable("container.waystones.waystone_selection"));

        Balm.networking().openMenu(player, menuProvider);
    }

    /**
     * 造一块传送石，只用来告诉 Waystones"这次按传送石计价"。
     *
     * 它不进玩家背包、不被消耗、也不会掉耐久——扣耐久的回调我们没注册。
     *
     * 走注册表按 id 取，而不是引用对方的 ModItems.warpStone：那是它的内部
     * 类，字段改个名我们就编译不过；而 waystones:warp_stone 这个注册名被
     * 配方、标签、命令引用着，是对方事实上的公开契约，稳得多。
     *
     * 取不到时返回空堆——那说明对方改了注册名，此时退化成"没有来源物品"，
     * 界面照开、经验照扣，只是与传送石绑定的规则不再命中。比抛异常好：
     * 玩家至少还能传送。
     */
    private static ItemStack warpStoneStack() {
        Item item = BuiltInRegistries.ITEM.get(
                ResourceLocation.fromNamespaceAndPath(WAYSTONES_MODID, "warp_stone"));
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }
}
