package com.november.mcphone.core.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.ModCapabilities;
import com.november.mcphone.core.PhoneItem;
import com.november.mcphone.core.menu.ModMenus;
import com.november.mcphone.core.menu.PhoneContainerMenu;
import com.november.mcphone.feature.enderchest.net.OpenEnderChestPacket;
import com.november.mcphone.feature.store.AppAccess;
import com.november.mcphone.feature.waystone.net.OpenWaystoneSelectionPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import com.november.mcphone.core.PhoneItemData;
import com.november.mcphone.feature.settings.WallpaperData;
import com.november.mcphone.feature.settings.net.SetDeviceNamePacket;
import com.november.mcphone.feature.settings.net.SetWallpaperPacket;
import com.november.mcphone.feature.settings.net.SyncWallpaperPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 网络包注册与处理 —— 与 NeoForge 那一支同名同职责的类。
 *
 * 与那一支同名同职责：本类只做"注册总入口"，各功能的包在自己的
 * XxxNetworking 里注册与处理。
 *
 * 唯一的差别是注册方式：那边把 TYPE + STREAM_CODEC + 处理函数交给
 * PayloadRegistrar；这边交给 MCphoneNetwork，由它统一兜住 setPacketHandled、
 * enqueueWork 与 getSender 判空三件事，见那个类的注释。
 *
 * 注册顺序就是包的线上身份，不能随便动，理由见 MCphoneNetwork 的类注释。
 */
public final class NetworkHandler {

    private NetworkHandler() {}

    // 要服务端干活、且已定价的内建 App。id 写在这里而不是每处现拼：
    // 拼错了不会报错，只会变成"未定价"从而静默放行——那正好是这道闸要防的事
    private static final ResourceLocation APP_ENDER_CHEST =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "ender_chest");

    private static final ResourceLocation APP_WAYSTONE =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "waystone");

    /**
     * 告诉玩家这个 App 还没买。
     *
     * 正常客户端走不到这里——没买过的付费 App 会被客户端从主屏摘掉，点都点不着。
     * 能走到这里说明客户端状态没对上，或者有人在伪造包，两种情况都值得回一句话。
     */
    private static void notPurchased(ServerPlayer player) {
        player.displayClientMessage(
                Component.translatable("mcphone.store.not_purchased")
                        .withStyle(ChatFormatting.RED), true);
    }

    /** 由 MCphone 构造函数调用。对应那边挂在 RegisterPayloadHandlersEvent 上的注册 */
    public static void register() {
        // C2S: 玩家选了壁纸
        MCphoneNetwork.registerToServer(
                SetWallpaperPacket.class,
                SetWallpaperPacket::encode,
                SetWallpaperPacket::decode,
                NetworkHandler::handleSetWallpaper
        );

        // S2C: 同步壁纸给玩家
        MCphoneNetwork.registerToClient(
                SyncWallpaperPacket.class,
                SyncWallpaperPacket::encode,
                SyncWallpaperPacket::decode,
                NetworkHandler::handleSyncWallpaper
        );

        // C2S: 玩家给手机起名
        MCphoneNetwork.registerToServer(
                SetDeviceNamePacket.class,
                SetDeviceNamePacket::encode,
                SetDeviceNamePacket::decode,
                NetworkHandler::handleSetDeviceName
        );

        // C2S: 玩家在手机里点了末影箱
        MCphoneNetwork.registerToServer(
                OpenEnderChestPacket.class,
                OpenEnderChestPacket::encode,
                OpenEnderChestPacket::decode,
                NetworkHandler::handleOpenEnderChest
        );

        // 各功能的包各自成组，注册与处理都在自己的类里：
        // 本类只保留"注册总入口"这一个职责，不做杂物间。
        //
        // 【顺序即身份】：SimpleChannel 按注册顺序发放整数序号，往末尾追加
        // 安全，中间插入或调换必须把 MCphoneNetwork.PROTOCOL_VERSION 加一
        com.november.mcphone.feature.chat.net.ChatNetworking.register();
        com.november.mcphone.feature.notes.net.NotesNetworking.register();
        com.november.mcphone.feature.store.net.StoreNetworking.register();
        com.november.mcphone.feature.music.net.MusicNetworking.register();

        // C2S: 玩家在手机里点了传送石。
        //
        // 【放在最末尾】而不是跟上面那几个内建 App 排在一起：往末尾追加不会
        // 挪动任何一个已有包的序号，也就不用动 PROTOCOL_VERSION。插在中间
        // 是能编过的，代价是老客户端把 A 包当 B 包解码
        MCphoneNetwork.registerToServer(
                OpenWaystoneSelectionPacket.class,
                OpenWaystoneSelectionPacket::encode,
                OpenWaystoneSelectionPacket::decode,
                NetworkHandler::handleOpenWaystoneSelection
        );
    }

    /**
     * 服务端收到：给玩家打开他自己的末影箱，界面装在手机机身里。
     *
     * 校验玩家身上确实带着手机——包是客户端发的，不能信。没有这道检查，
     * 任何人改个客户端就能凭空开末影箱，手机这个前提条件形同虚设。
     *
     * 容器直接用 player.getEnderChestInventory()，就是原版那一个：与方块末影箱、
     * 跨维度完全互通，不另存一份数据，也就不存在两边不同步的问题。
     */
    private static void handleOpenEnderChest(OpenEnderChestPacket packet, ServerPlayer player) {
        if (!PhoneItem.isCarriedBy(player)) {
            MCphone.LOGGER.debug("玩家 {} 请求开末影箱但身上没有手机，已忽略",
                    player.getName().getString());
            return;
        }

        // 买过了吗。安装是纯客户端动作，改个客户端就能把 App 塞进主屏，
        // 购买那一步完全绕开——所以服务端必须自己问一句
        if (!AppAccess.canUse(player, APP_ENDER_CHEST)) {
            notPurchased(player);
            return;
        }

        PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, p) -> new PhoneContainerMenu(
                        ModMenus.ENDER_CHEST.get(), containerId, inventory,
                        enderChest, ModMenus.ENDER_CHEST_SIZE),
                Component.translatable("mcphone.container.ender_chest")));
    }

    /**
     * 服务端收到：给玩家打开传送石碑的选点界面。
     *
     * 开不成时给一句 actionbar 提示。玩家点了图标、界面却没弹出来，什么都不
     * 说是最糟的一种失败——他会以为是自己点错了，反复点，然后来报"手机坏了"。
     * 一句话就能把他引向真正的原因：服务端没装传送石碑，或者版本对不上。
     *
     * 这条路在正常情况下走不到：没装 Waystones 时那个 App 压根不登记，客户端
     * 也就发不出这个包。能走到这里，说明两端装的模组不一致、对方改了 API，
     * 或者有人在伪造包——前两种玩家有权知道，第三种告诉他也无妨。
     *
     * 主线程与 player 非空由 MCphoneNetwork.registerToServer 保证，这里不必再判。
     */
    private static void handleOpenWaystoneSelection(OpenWaystoneSelectionPacket packet,
                                                    ServerPlayer player) {
        if (!PhoneItem.isCarriedBy(player)) {
            MCphone.LOGGER.debug("玩家 {} 请求开传送石但身上没有手机，已忽略",
                    player.getName().getString());
            return;
        }

        // 买过了吗。安装是纯客户端动作，改个客户端就能把 App 塞进主屏，
        // 购买那一步完全绕开——所以服务端必须自己问一句
        if (!AppAccess.canUse(player, APP_WAYSTONE)) {
            notPurchased(player);
            return;
        }

        if (!com.november.mcphone.compat.WaystonesCompat.openSelection(player)) {
            // true = 显示在物品栏上方那一行，不占聊天记录。与 Waystones
            // 自己报传送失败时的位置一致，玩家不会觉得是两个模组在说话
            player.displayClientMessage(
                    Component.translatable("mcphone.waystone.unavailable"), true);
        }
    }

    //  处理函数

    /**
     * 服务端收到：记录壁纸选择，回发给该玩家的客户端。
     *
     * 与那边逐行对应，只有两处不同：
     *   - player.setData(WALLPAPER, ...)  →  ModCapabilities.of(player).setWallpaper(...)
     *   - ctx.reply(...)                  →  MCphoneNetwork.sendToPlayer(player, ...)
     *
     * 主线程与 player 非空由 MCphoneNetwork.registerToServer 保证，这里不必再判。
     */
    private static void handleSetWallpaper(SetWallpaperPacket packet, ServerPlayer player) {
        ModCapabilities.of(player).setWallpaper(new WallpaperData(packet.wallpaperFileName()));

        // 发回给该玩家确认
        MCphoneNetwork.sendToPlayer(player, new SyncWallpaperPacket(packet.wallpaperFileName()));

        MCphone.LOGGER.debug("玩家 {} 设置壁纸: {}", player.getName().getString(),
                packet.wallpaperFileName().isEmpty() ? "默认" : packet.wallpaperFileName());
    }

    /**
     * 服务端收到：把设备名写进玩家指定的那一部手机。
     *
     * 客户端发来的东西一律不信：
     *   - 那个位置上确实是手机吗（否则就能给任意物品改名了，何况位置本身
     *     就可能已经失效——手机被丢掉了）
     *   - 名字再清洗一遍（客户端可以是伪造的，绕过界面直接发包）
     *
     * 手上与背包里的改完不必手动同步：玩家背包容器每 tick 会比对上一次的快照，
     * 物品变了就自动下发。饰品栏不归原版管，故统一调一次 writeBack，
     * 由位置自己决定要不要动作。
     *
     * 与那一支只差存法：那边写的是数据组件，这边是 NBT（见 PhoneItemData）。
     */
    private static void handleSetDeviceName(SetDeviceNamePacket packet, ServerPlayer player) {
        ItemStack stack = packet.location().resolve(player);

        // 那个位置上不是手机就什么都不做：位置由客户端给出，可能已经失效
        // （手机被丢掉了），也可能是伪造的
        if (!PhoneItem.isPhone(stack)) return;

        String name = SetDeviceNamePacket.sanitize(packet.name());
        if (name.isEmpty()) {
            // 空名字＝清除设备名，恢复默认物品名。
            // 移除而不是存空串，"没起过名"与"起了空名"不该混淆
            PhoneItemData.clearDeviceName(stack);
        } else {
            PhoneItemData.setDeviceName(stack, name);
        }

        // 手上与背包里的改完原版自会同步，饰品栏得显式写回去通知 Curios
        packet.location().writeBack(player, stack);

        MCphone.LOGGER.debug("玩家 {} 设置设备名: {}", player.getName().getString(),
                name.isEmpty() ? "(清除)" : name);
    }

    /**
     * 客户端收到：更新本地缓存的壁纸纹理引用（PhoneScreen 每帧查询）。
     *
     * 这个方法【不碰任何客户端专有类型】——只写一个静态字段。所以本类在专用
     * 服务端上加载没有问题。往这里加 S2C 处理时要守住这条：一旦出现
     * Minecraft.getInstance() 之类，就得挪到只在客户端加载的类里去。
     */
    private static void handleSyncWallpaper(SyncWallpaperPacket packet) {
        WakeholderData.setWallpaperFileName(packet.wallpaperFileName());
    }

    /**
     * 客户端本地壁纸缓存 —— 在 PhoneScreen 渲染时读取。
     * 放到这个独立 holder 类中避免 PhoneScreen 直接依赖 network 包。
     */
    public static final class WakeholderData {
        private static String currentWallpaper = "";

        public static String get() { return currentWallpaper; }
        static void setWallpaperFileName(String name) { currentWallpaper = name; }
    }
}
