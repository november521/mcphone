package com.november.mcphone.core.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.ModCapabilities;
import com.november.mcphone.core.PhoneItem;
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
 * 那边这里有 5 个包加 4 组转发，一共 34 个。这边现在有三个：壁纸那一对
 * （C2S + S2C）与设备名。它们正好是「设置」App 用到的全部，也就是说这一支
 * 眼下能跑通的那条链路是完整的，不是半截。
 *
 * 其余 31 个包的【客户端】那一半已经跟着界面搬过来了，服务端这一半还没有，
 * 所以对应的 App 暂时不登记进 SPI 清单（见 resources/META-INF/services）——
 * 登记了点进去只会发出一个没人接的包。每搬完一个功能的服务端，
 * 在这里加注册、在那份清单里加一行。
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

        // C2S: 玩家给手机起名
        MCphoneNetwork.registerToServer(
                SetDeviceNamePacket.class,
                SetDeviceNamePacket::encode,
                SetDeviceNamePacket::decode,
                NetworkHandler::handleSetDeviceName
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
