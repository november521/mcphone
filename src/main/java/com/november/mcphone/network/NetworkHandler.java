package com.november.mcphone.network;

import com.november.mcphone.MCphone;
import com.november.mcphone.ModDataComponents;
import com.november.mcphone.PhoneItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络包处理 —— 注册并处理所有 MCphone 网络包。
 *
 * 注册入口：在 MCphone 构造函数中通过
 * modEventBus.addListener(NetworkHandler::register) 挂载。
 */
public final class NetworkHandler {

    private NetworkHandler() {}

    // ---- 由 MCphone 构造函数调用 ----
    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        // C2S: 玩家选了壁纸
        registrar.playToServer(
                SetWallpaperPacket.TYPE,
                SetWallpaperPacket.STREAM_CODEC,
                NetworkHandler::handleSetWallpaper
        );

        // S2C: 同步壁纸给玩家
        registrar.playToClient(
                SyncWallpaperPacket.TYPE,
                SyncWallpaperPacket.STREAM_CODEC,
                NetworkHandler::handleSyncWallpaper
        );

        // C2S: 玩家给手机起名
        registrar.playToServer(
                SetDeviceNamePacket.TYPE,
                SetDeviceNamePacket.STREAM_CODEC,
                NetworkHandler::handleSetDeviceName
        );
    }

    // ============================================================
    //  处理函数
    // ============================================================

    /** 服务端收到：记录壁纸选择，广播给该玩家的客户端 */
    private static void handleSetWallpaper(SetWallpaperPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var player = ctx.player();
            player.setData(WallpaperData.TYPE.get(), new WallpaperData(packet.wallpaperFileName()));

            // 发回给该玩家确认
            ctx.reply(new SyncWallpaperPacket(packet.wallpaperFileName()));

            MCphone.LOGGER.debug("玩家 {} 设置壁纸: {}", player.getName().getString(),
                    packet.wallpaperFileName().isEmpty() ? "默认" : packet.wallpaperFileName());
        });
    }

    /**
     * 服务端收到：把设备名写进玩家手上那只手机的数据组件。
     *
     * 客户端发来的东西一律不信：
     *   - 手上拿的确实是手机吗（否则就能给任意物品改名了）
     *   - 名字再清洗一遍（客户端可以是伪造的，绕过界面直接发包）
     *
     * 改完不必手动同步：玩家背包容器每 tick 会用 ItemStack.matches
     * 比对上一次的快照，组件变了就会自动下发给客户端。
     */
    private static void handleSetDeviceName(SetDeviceNamePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var player = ctx.player();
            InteractionHand hand = packet.mainHand() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            ItemStack stack = player.getItemInHand(hand);

            if (!(stack.getItem() instanceof PhoneItem)) return;

            String name = SetDeviceNamePacket.sanitize(packet.name());
            if (name.isEmpty()) {
                // 空名字＝清除设备名，恢复默认物品名。
                // 移除组件而不是存空串，"没起过名"与"起了空名"不该混淆
                stack.remove(ModDataComponents.DEVICE_NAME.get());
            } else {
                stack.set(ModDataComponents.DEVICE_NAME.get(), name);
            }

            MCphone.LOGGER.debug("玩家 {} 设置设备名: {}", player.getName().getString(),
                    name.isEmpty() ? "(清除)" : name);
        });
    }

    /** 客户端收到：更新本地缓存的壁纸纹理引用（PhoneScreen 每帧查询） */
    private static void handleSyncWallpaper(SyncWallpaperPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            WakeholderData.setWallpaperFileName(packet.wallpaperFileName());
        });
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
