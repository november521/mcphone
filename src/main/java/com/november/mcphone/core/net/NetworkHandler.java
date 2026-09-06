package com.november.mcphone.core.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.core.ModDataComponents;
import com.november.mcphone.core.PhoneItem;
import com.november.mcphone.core.menu.ModMenus;
import com.november.mcphone.core.menu.PhoneContainerMenu;
import com.november.mcphone.feature.enderchest.net.OpenEnderChestPacket;
import com.november.mcphone.feature.settings.WallpaperData;
import com.november.mcphone.feature.settings.net.SetDeviceNamePacket;
import com.november.mcphone.feature.settings.net.SetWallpaperPacket;
import com.november.mcphone.feature.settings.net.SyncWallpaperPacket;
import com.november.mcphone.feature.store.AppAccess;
import com.november.mcphone.feature.waystone.net.OpenWaystoneSelectionPacket;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;

/**
 * 网络包处理（服务端一半）—— 注册 C2S 包并处理服务端收到的请求。
 *
 * 原 NeoForge 版一次注册全部方向；Fabric 的 ClientPlayNetworking 是客户端专用
 * 类，不能出现在这个会被专用服务器加载的类里。所以 S2C 的注册与客户端接收
 * 全部挪到 {@code core/client/ClientNetworking} 与各 feature 的 client 网络类，
 * 本类只保留 {@link #registerServer()}。
 *
 * Fabric 的服务端接收回调已经跑在服务端主线程上，原来的 enqueueWork 包装去掉。
 */
public final class NetworkHandler {

    private NetworkHandler() {}

    // 两个要服务端干活、且已定价的内建 App。id 写在这里而不是每处现拼：
    // 拼错了不会报错，只会变成"未定价"从而静默放行——那正好是这道闸要防的事
    private static final ResourceLocation APP_ENDER_CHEST =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "ender_chest");
    private static final ResourceLocation APP_WAYSTONE =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "waystone");

    /**
     * 告诉玩家这个 App 还没买。
     *
     * 正常客户端走不到这里——没买过的付费 App 会被客户端从主屏摘掉（见
     * PhoneScreenRegistry.enforcePurchases），点都点不着。能走到这里说明
     * 客户端状态没对上，或者有人在伪造包，两种情况都值得回一句话。
     */
    private static void notPurchased(ServerPlayer player) {
        player.displayClientMessage(
                Component.translatable("mcphone.store.not_purchased")
                        .withStyle(ChatFormatting.RED), true);
    }

    /** C2S：注册 + 服务端接收，由 MCphone.onInitialize 调用 */
    public static void registerServer() {
        PayloadTypeRegistry.playC2S().register(SetWallpaperPacket.TYPE, SetWallpaperPacket.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SetWallpaperPacket.TYPE, NetworkHandler::handleSetWallpaper);

        PayloadTypeRegistry.playC2S().register(SetDeviceNamePacket.TYPE, SetDeviceNamePacket.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SetDeviceNamePacket.TYPE, NetworkHandler::handleSetDeviceName);

        PayloadTypeRegistry.playC2S().register(OpenEnderChestPacket.TYPE, OpenEnderChestPacket.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(OpenEnderChestPacket.TYPE, NetworkHandler::handleOpenEnderChest);

        // 无条件注册，不看服务端装没装 Waystones：网络包类型的注册两端必须
        // 对称，少注册一个，装了 Waystones 的客户端发来这个包时，服务端会
        // 因为不认识它而把玩家踢下线。装没装的判断放在处理函数里。
        PayloadTypeRegistry.playC2S().register(OpenWaystoneSelectionPacket.TYPE, OpenWaystoneSelectionPacket.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(OpenWaystoneSelectionPacket.TYPE, NetworkHandler::handleOpenWaystoneSelection);

        // 聊天/记事本/商店/音乐的包各自成组，注册与处理都在自己的类里：
        // 本类只保留"注册总入口"这一个职责，不做杂物间
        com.november.mcphone.feature.chat.net.ChatNetworking.registerServer();
        com.november.mcphone.feature.notes.net.NotesNetworking.registerServer();
        com.november.mcphone.feature.store.net.StoreNetworking.registerServer();
        com.november.mcphone.feature.music.net.MusicNetworking.registerServer();
    }

    //  处理函数（服务端）

    /** 服务端收到：记录壁纸选择，广播给该玩家的客户端 */
    private static void handleSetWallpaper(SetWallpaperPacket packet, ServerPlayNetworking.Context ctx) {
        var player = ctx.player();
        player.setAttached(ModAttachments.WALLPAPER, new WallpaperData(packet.wallpaperFileName()));

        // 发回给该玩家确认
        ServerPlayNetworking.send(player, new SyncWallpaperPacket(packet.wallpaperFileName()));

        MCphone.LOGGER.debug("玩家 {} 设置壁纸: {}", player.getName().getString(),
                packet.wallpaperFileName().isEmpty() ? "默认" : packet.wallpaperFileName());
    }

    /**
     * 服务端收到：把设备名写进玩家指定的那一部手机。
     *
     * 客户端发来的东西一律不信：那个位置上确实是手机吗、名字再清洗一遍。
     */
    private static void handleSetDeviceName(SetDeviceNamePacket packet, ServerPlayNetworking.Context ctx) {
        var player = ctx.player();
        ItemStack stack = packet.location().resolve(player);

        if (!PhoneItem.isPhone(stack)) return;

        String name = SetDeviceNamePacket.sanitize(packet.name());
        if (name.isEmpty()) {
            stack.remove(ModDataComponents.DEVICE_NAME);
        } else {
            stack.set(ModDataComponents.DEVICE_NAME, name);
        }

        // 手上与背包里的改完原版自会同步，饰品栏已从本版移除（Curios 在 1.21.1 Fabric 无构建）
        packet.location().writeBack(player, stack);

        MCphone.LOGGER.debug("玩家 {} 设置设备名: {}", player.getName().getString(),
                name.isEmpty() ? "(清除)" : name);
    }

    /**
     * 服务端收到：给玩家打开他自己的末影箱，界面装在手机机身里。
     * 校验玩家身上确实带着手机——包是客户端发的，不能信。
     */
    private static void handleOpenEnderChest(OpenEnderChestPacket packet, ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();

        if (!PhoneItem.isCarriedBy(player)) {
            MCphone.LOGGER.debug("玩家 {} 请求开末影箱但身上没有手机，已忽略",
                    player.getName().getString());
            return;
        }

        if (!AppAccess.canUse(player, APP_ENDER_CHEST)) {
            notPurchased(player);
            return;
        }

        PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, p) -> new PhoneContainerMenu(
                        ModMenus.ENDER_CHEST, containerId, inventory,
                        enderChest, ModMenus.ENDER_CHEST_SIZE),
                Component.translatable("mcphone.container.ender_chest")));
    }

    /**
     * 服务端收到：给玩家打开传送石碑的选点界面。
     * 校验与末影箱一致——身上得真有手机。
     */
    private static void handleOpenWaystoneSelection(OpenWaystoneSelectionPacket packet,
                                                    ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();

        if (!PhoneItem.isCarriedBy(player)) {
            MCphone.LOGGER.debug("玩家 {} 请求开传送石但身上没有手机，已忽略",
                    player.getName().getString());
            return;
        }

        if (!AppAccess.canUse(player, APP_WAYSTONE)) {
            notPurchased(player);
            return;
        }

        if (!com.november.mcphone.compat.WaystonesCompat.openSelection(player)) {
            player.displayClientMessage(
                    Component.translatable("mcphone.waystone.unavailable"), true);
        }
    }

    /**
     * 客户端本地壁纸缓存 —— 在 PhoneScreen/PhoneChassis 渲染时读取。
     * 放到这个独立 holder 类中避免 UI 直接依赖 network 包。纯静态字符串，
     * 不含任何客户端类型，放在本类里不会被专用服务器判为违规。
     */
    public static final class WakeholderData {
        private static String currentWallpaper = "";

        public static String get() { return currentWallpaper; }
        public static void setWallpaperFileName(String name) { currentWallpaper = name; }
    }
}
