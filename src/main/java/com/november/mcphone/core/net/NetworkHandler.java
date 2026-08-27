package com.november.mcphone.core.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.ModCapabilities;
import com.november.mcphone.feature.settings.WallpaperData;
import com.november.mcphone.feature.settings.net.SetWallpaperPacket;
import com.november.mcphone.feature.settings.net.SyncWallpaperPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网络包注册与处理 —— 与 NeoForge 那一支同名同职责的类。
 *
 * 那边这里有 5 个包加 4 组转发，一共 34 个。这边现在只有壁纸那一对：
 * 网络层是整个移植的总闸（见 docs/PORTING.md 的第二刀归因表），所以先只通
 * 一条路，把 SimpleChannel 的骨架、两个方向、线程调度、存档落盘全跑通，
 * 确认形状对了，剩下的 32 个再照着搬。
 *
 * 注册顺序就是包的线上身份，不能随便动，理由见 MCphoneNetwork 的类注释。
 */
public final class NetworkHandler {

    private NetworkHandler() {}

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
