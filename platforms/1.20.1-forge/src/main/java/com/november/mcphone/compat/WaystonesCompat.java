package com.november.mcphone.compat;

import com.november.mcphone.MCphone;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.menu.BalmMenuProvider;
import net.blay09.mods.waystones.api.IWaystoneTeleportContext;
import net.blay09.mods.waystones.api.WaystoneTeleportEvent;
import net.blay09.mods.waystones.core.WarpMode;
import net.blay09.mods.waystones.menu.WaystoneSelectionMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * Waystones（传送石碑）兼容层 —— 让手机里的「传送石」App 打开它的选点界面。
 *
 * 我们只做一件事：把对方的菜单端上来
 *
 * 选点列表、排序、搜索、分组、维度判定、经验扣除、传送本身、粒子与音效，
 * 全是 Waystones 的。我们不画界面、不注册菜单类型、不碰传送逻辑，只是在
 * 玩家点手机图标时，替他做一次它自己也会做的调用。
 *
 * 参照的是对方 WarpStoneItem.finishUsingItem —— 那里就是"拼一个
 * BalmMenuProvider，交给 Balm 打开"。
 *
 * 与 1.21.1 那一支的差别：整条路都不一样，别照抄
 *
 * 那边有个现成的 WaystoneSelectionListBuilder，一句
 * .withTargetsForItem(warpStoneStack()).buildMenuProvider(...) 就同时定下了
 * "去哪些点"和"按哪种来源计价"。Waystones 14.x（1.20.1）上：
 *
 *   - 【没有 WaystoneSelectionListBuilder 这个类】。菜单由
 *     {@link WaystoneSelectionMenu#createWaystoneSelection} 直接造，目标列表
 *     它自己从 PlayerWaystoneManager.getWaystones(player) 取 —— 就是玩家
 *     自己那份已激活清单，与 warpMode 无关
 *   - 【没有 ModMenus.warpStoneSelection】，也没有 TeleportFlags。"这次传送算
 *     哪种来源"改由一个 {@link WarpMode} 枚举表示，直接传进菜单
 *   - Balm 的方法名也不同：Balm.networking().openMenu(...) →
 *     Balm.getNetworking().openGui(...)
 *
 * 为什么用 WARP_STONE 而不是 CUSTOM
 *
 * 这一支能选的只有两个：
 *
 *   WARP_STONE  经验代价按服主配的 warpStoneXpCostMultiplier、冷却按
 *               warpStoneCooldown，但要求玩家身上有一块传送石（见下一段）
 *   CUSTOM      xpCostMultiplier 恒为 0、不进 applyCooldown、不要任何物品
 *
 * CUSTOM 看着省事，代价是这个 App 变成一部【无限次的免费传送器】—— 服主
 * 为传送石配的任何代价，它一条都不命中。那不是取舍，是把平衡拆了。
 *
 * 1.1.4 曾经在那一支上把这个 App 声明成 INVENTORY_BUTTON，那是错的（它会
 * 白白吃上背包按钮那 5 分钟冷却）；这一支上它更糟 —— canUseInventoryButton
 * 要求玩家先在配置里绑定一个传送点，没绑就是个按了没反应的死按钮。
 *
 * 所以是 WARP_STONE：服主怎么配传送石，这个 App 就怎么走。
 *
 * 但 WARP_STONE 有个前提要我们自己补上
 *
 * 14.x 判"能不能用这个模式"，看的是 context.getWarpItem()：
 *
 *     case WARP_STONE -> !warpItem.isEmpty()
 *             && warpItem.is(ModItemTags.WARP_STONES)
 *             && entity instanceof Player player
 *             && canUseWarpStone(player, warpItem);
 *
 * 而那个 warpItem 是它自己从【主手与副手】里找的（findWarpItem，只看这两处，
 * 不翻背包）。手机可以躺在背包里、也可以挂在饰品栏里 ——
 * {@link com.november.mcphone.core.client.PhoneScreenOpener} 三处都找 ——
 * 玩家开着手机时手上多半就是手机本身，不是传送石。照原样接上去，这个 App
 * 在多数情况下都是"点了没反应"。
 *
 * 那一支是靠 withTargetsForItem(warpStoneStack()) 把 warpItem 显式塞进去的；
 * 这一支菜单不收这个参数，只好换个时机塞：{@link #registerWarpItemHook()}
 * 挂在 WaystoneTeleportEvent.Pre 上，在它取 warpItem 之前把一块传送石放进
 * 上下文。字节码里的顺序是
 *
 *     fireEvent(Pre) -> isCanceled? -> getWarpItem() -> canUseWarpMode(...)
 *
 * 14.0.0 与 14.1.20 上都是这个顺序，两头都核过。
 *
 * 那块石头不进背包、不被消耗、也不掉耐久
 *
 * WarpMode.WARP_STONE 的 consumesItem 是 false，而整个 Waystones 14.x 的 jar
 * 里【一处 hurtAndBreak 都没有】—— 这一支的传送石付的是冷却，不是耐久
 * （1.21.1 那边才是耐久）。何况我们塞进去的本来就是临时造的一只 ItemStack，
 * 不在任何容器里，就算它想扣也扣不到玩家头上。
 *
 * 类型隔离的规矩，和 CuriosCompat 一模一样
 *
 * Waystones 是可选依赖，编译期有类、运行期可能一个都没有。JVM 准备执行
 * 一个方法时会解析它引用到的类型，碰上不存在的类当场抛
 * NoClassDefFoundError —— 所以"装没装"的判断和"真去调它"必须分在两个方法
 * 里。写在同一个方法里的话，那句 if 还没轮到执行，方法本身就炸了。
 *
 * 别把 {@link #openSelection} 和 {@link #openSelectionInternal} 并回去，
 * 也别把 {@link #registerWarpItemHook} 里的内容挪进调用它的那个方法。
 *
 * 这个类会被服务端加载，所以一个客户端类都不许出现
 *
 * 调用它的是网络包处理函数与兼容模块装载，都是两端共用的代码。上面用到的
 * Balm 与 Waystones 的类都是两端安全的（对方自己的物品与网络层也在服务端
 * 用它们），原版类只用到 ServerPlayer、Player、Inventory 与 Component。
 *
 * 往这里加东西时守住这条线：任何 net.minecraft.client.* 或
 * com.mojang.blaze3d.* 出现在这里，专用服务器会启动即崩，而崩溃信息不会
 * 提到传送石。
 */
public final class WaystonesCompat {

    private WaystonesCompat() {}

    /**
     * 传送石碑的 modid。
     *
     * 公开的：传送石 App 要拿它声明联动（IPhoneApp.companionMods()），而这个
     * 字符串只该有一处权威来源。各写一份的话，改动时漏一处就会出现"商店里
     * 有、点了没反应"这种最难查的状态。
     *
     * 注意它是编译期常量，引用它【不会】加载本类。要判断在不在场请调
     * {@link #isLoaded()} —— 那才是会加载本类的那一个，而本类没有静态初始化
     * 块、唯一的静态字段就是这个字符串，所以加载它是安全的。
     */
    public static final String WAYSTONES_MODID = "waystones";

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
     * 只回答"开成了没有"，不管提示玩家 —— 那是措辞与翻译键的事，属于调用方。
     * 兼容层沾上 UX，以后想换提示方式就得动这里，而这里是最不该频繁改的地方。
     *
     * @return true 表示菜单已交给对方打开；没装 Waystones、或对方的 API 变了
     *         导致调用失败时返回 false。调用方必须处理 false —— 玩家点了图标，
     *         界面却没弹出来，什么都不说是最糟的一种失败
     */
    public static boolean openSelection(ServerPlayer player) {
        if (!isLoaded()) return false;

        // 兜 Throwable 而不是 Exception：这里最可能的翻车方式是对方改了
        // 类名或方法签名，那抛出来的是 NoClassDefFoundError / NoSuchMethodError
        // ——都属于 Error，用 Exception 接不住。
        //
        // 兜住的代价是玩家点了没反应；不兜的代价是整个网络包处理函数抛异常，
        // 而 Forge 在那条路径上收到异常会把玩家踢下线。
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
     * 单独一个方法，只在上面确认装了之后才会被调到 —— 理由见类注释，
     * 别把它并回去。
     */
    private static void openSelectionInternal(ServerPlayer player) {
        Balm.getNetworking().openGui(player, new WarpStoneSelectionProvider());
    }

    /**
     * 与对方 WarpStoneItem 里那个匿名 BalmMenuProvider 逐行对应的一份。
     *
     * 为什么不复用它的：那是 WarpStoneItem 的一个 private static 字段，拿不到。
     * 三个方法照抄一遍比反射稳得多。
     *
     * 写成具名的静态嵌套类而不是匿名类，只是为了让它在栈里有个认得出的名字
     * —— 加载时机是一样的：两者都只在 {@link #openSelectionInternal} 真被执行
     * 到时才解析。
     */
    private static final class WarpStoneSelectionProvider implements BalmMenuProvider {

        /**
         * 标题直接用对方的翻译键：玩家看到的界面就是 Waystones 的界面，
         * 顶上却写着 MCphone 的字样反而割裂，何况那样还得我们自己翻两份。
         */
        @Override
        public Component getDisplayName() {
            return Component.translatable("container.waystones.waystone_selection");
        }

        /**
         * 服务端这一侧的菜单。第四个参数是"从哪个传送点出发"，只有
         * WAYSTONE_TO_WAYSTONE 用得上，这里必须是 null。
         */
        @Override
        public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
            return WaystoneSelectionMenu.createWaystoneSelection(
                    containerId, player, WarpMode.WARP_STONE, null);
        }

        /**
         * 客户端重建菜单时读的就是这一个字节。
         *
         * 【必须写】：对方的客户端工厂第一句就是
         * WarpMode.values[buf.readByte()]，不写就是从一个空 buf 里读，玩家
         * 那边直接抛异常。
         *
         * 也【只能】写这一个字节：只有 WAYSTONE_TO_WAYSTONE 那一支会再读一个
         * BlockPos，我们不是那一支，多写反而对不上。
         */
        @Override
        public void writeScreenOpeningData(ServerPlayer player, FriendlyByteBuf buf) {
            buf.writeByte(WarpMode.WARP_STONE.ordinal());
        }
    }

    /**
     * 挂上"手机等于一块传送石"这条钩子。由 {@link WaystonesWarpItemModule} 在
     * 确认装了 Waystones 之后调用一次。
     *
     * 只在 warpItem 空着时才塞。真拿着石头传送的那条路 warpItem 必然非空
     * （对方自己的 findWarpItem 已经找到了），我们碰都不碰它 —— 换句话说，
     * 这条钩子实际只对本 App 开出来的菜单生效。
     *
     * 单独一个方法：它一执行就要解析 Balm 与 Waystones 的类型，理由见类注释。
     */
    public static void registerWarpItemHook() {
        Balm.getEvents().onEvent(WaystoneTeleportEvent.Pre.class, event -> {
            // 回调体内必须自己兜一层：事件是由对方的总线发起的，那时候栈上
            // 早已没有装载期的 try。这里放跑一个异常，赔上的是玩家这一次
            // 传送，而不是我们这一个 App。见 CompatModules 的类注释
            try {
                IWaystoneTeleportContext context = event.getContext();
                if (context.getWarpMode() != WarpMode.WARP_STONE) return;
                if (!context.getWarpItem().isEmpty()) return;

                ItemStack warpStone = warpStoneStack();
                if (warpStone != null) context.setWarpItem(warpStone);
            } catch (Throwable t) {
                MCphone.LOGGER.error("补传送石来源物品失败（Waystones 版本可能不兼容）", t);
            }
        });
    }

    /**
     * 造一块传送石，只用来告诉 Waystones"这次按传送石计价"。
     *
     * 它不进玩家背包、不被消耗、也不会掉耐久，理由见类注释。
     *
     * 走注册表按 id 取，而不是引用对方的 ModItems.warpStone：那是它的内部类，
     * 字段改个名我们就编译不过；而 waystones:warp_stone 这个注册名被配方、
     * 标签、命令引用着，是对方事实上的公开契约，稳得多。
     *
     * 取不到时返回 null，调用方原样不动 —— 那说明对方改了注册名。此时
     * warpItem 仍是空的，这次传送会被它以 WarpModeRejected 挡下。挡下比放行
     * 好：放行意味着我们绕过了服主为传送石配的全部规则。
     */
    @Nullable
    private static ItemStack warpStoneStack() {
        Item item = BuiltInRegistries.ITEM.get(
                ResourceLocation.fromNamespaceAndPath(WAYSTONES_MODID, "warp_stone"));
        return item == Items.AIR ? null : new ItemStack(item);
    }
}
