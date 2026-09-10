package com.november.mcphone;

import com.mojang.logging.LogUtils;
import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.core.ModCreativeTabs;
import com.november.mcphone.core.ModDataComponents;
import com.november.mcphone.core.ModItems;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * 主入口（服务端 + 共用端）。原 NeoForge 版是 {@code @Mod} 构造器里做注册与挂事件；
 * Fabric 把它拆成 {@link ModInitializer}（本类）与 {@link MCphoneClient}
 * （{@code ClientModInitializer}，客户端）。
 */
public class MCphone implements ModInitializer {

    public static final String MODID = "mcphone";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 运行时的真实版本号，tooltip 用它填 %s，语言文件里不写死。
     * 在 onInitialize 里从 Fabric Loader 的模组元数据取。
     */
    private static String version = "";

    @Override
    public void onInitialize() {
        // 注册类都是"引用即注册"（ModItems 的静态字段在类加载时注册进注册表），
        // 这里逐个摸一遍，保证类都被加载。顺序无谓，但集中在一处看得全。
        // 物品：ModItems 的静态初始化器把 PHONE/TABLET 注册进注册表。显式摸一下这两个
        // 持有者，保证注册发生在 onInitialize 里，而不是等第一次用到才悄悄发生
        //（Fabric 的注册是即时的，摸到就是注册完了）
        ModItems.PHONE.get();
        ModItems.TABLET.get();
        ModDataComponents.register();
        ModCreativeTabs.register();
        com.november.mcphone.core.menu.ModMenus.register();
        com.november.mcphone.core.ModSounds.register();
        ModAttachments.ensureLoaded();

        // 网络：C2S 的注册 + 服务端接收（S2C 的编解码在这里一并登记，
        // 客户端的接收器由 core/client/ClientNetworking 在客户端启动时挂上）
        com.november.mcphone.core.net.NetworkHandler.registerServer();

        // 游戏生命周期 —— 对应原 NeoForge 的 NeoForge.EVENT_BUS 几条
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var id = handler.getPlayer().getUUID();
            com.november.mcphone.core.net.RequestThrottle.onPlayerLoggedOut(id);
            com.november.mcphone.feature.music.DiscService.onPlayerLoggedOut(id);
            com.november.mcphone.feature.chat.ChatImageUploads.onPlayerLoggedOut(id);
        });

        // 开服时清掉没有消息认领的图片文件（理由见 ChatImageStore.sweepOrphans）
        // 并加载/生成服务端配置
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            com.november.mcphone.feature.chat.ChatImageStore.onServerStarted(server);
            com.november.mcphone.core.ServerConfig.load(server);
        });

        // 手机替卡槽里的终端供电。漏了它的症状是"终端在手机里会没电"，见 TerminalCharger。
        // Fabric 没有 NeoForge 的按玩家 tick 事件，用服务端 tick 自己发
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                com.november.mcphone.feature.terminal.TerminalCharger.onPlayerTick(player);
            }
        });

        // 玩家进入世界时把服主那份服务端配置推给他（Fabric 没有 NeoForge 的自动同步，
        // shared/ 有三处客户端直读 ServerConfig，没有这个包按钮显隐会静默出错）
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var cfg = com.november.mcphone.core.ServerConfig.allowFriendTeleport();
            var img = com.november.mcphone.core.ServerConfig.allowChatImages();
            var kb = com.november.mcphone.core.ServerConfig.chatImageMaxBytes() / 1024;
            ServerPlayNetworking.send(handler.getPlayer(),
                    new com.november.mcphone.core.net.SyncServerConfigPacket(cfg, img, kb));

            // NeoForge 版登录时附件自动 sync；这里手工把终端卡槽的初值也推一次
            ServerPlayNetworking.send(handler.getPlayer(), new com.november.mcphone.core.net.SyncPhoneTerminalPacket(
                    handler.getPlayer().getAttachedOrCreate(com.november.mcphone.core.ModAttachments.PHONE_TERMINAL)));
        });

        // 兼容模块（能力型 + 缺陷型）统一装载。
        // 「终端」App 接哪几家存储模组（Terminals.discover）不在这里点数：RS 的
        // API 代理要等它自己初始化挂上，Fabric 没有全体初始化完的公共节点，
        // 那边改成懒触发 —— 第一次被问到时再挑，见 Terminals 的类注释
        com.november.mcphone.compat.CompatModules.init();

        version = FabricLoader.getInstance().getModContainer(MODID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("");

        LOGGER.info("MCphone 模组加载完成 —— 手机已就绪");
    }

    /** 本模组版本号，如 "1.0.0"。onInitialize 前调用会得到空串。 */
    public static String getVersion() {
        return version;
    }
}
