package com.november.mcphone.network;

import com.november.mcphone.MCphone;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络包处理 —— 注册并处理所有 MCphone 网络包。
 */
@EventBusSubscriber(modid = MCphone.MODID)
public final class NetworkHandler {

    private NetworkHandler() {}

    @SubscribeEvent
    static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        // ---- C2S: 客户端 → 服务端 —— 玩家选了壁纸 ----
        registrar.playToServer(
                SetWallpaperPacket.TYPE,
                SetWallpaperPacket.STREAM_CODEC,
                NetworkHandler::handleSetWallpaper
        );

        // ---- S2C: 服务端 → 客户端 —— 同步壁纸给玩家 ----
        registrar.playToClient(
                SyncWallpaperPacket.TYPE,
                SyncWallpaperPacket.STREAM_CODEC,
                NetworkHandler::handleSyncWallpaper
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
