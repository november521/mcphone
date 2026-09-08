package com.november.mcphone;

import com.mojang.logging.LogUtils;
import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.core.ModCreativeTabs;
import com.november.mcphone.core.ModDataComponents;
import com.november.mcphone.core.ModSounds;
import com.november.mcphone.core.PhoneItem;
import com.november.mcphone.core.menu.ModMenus;
import com.november.mcphone.core.net.SyncServerConfigPacket;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import org.slf4j.Logger;

/**
 * MCphone 的 Fabric 入口（主端）。
 *
 * 原 NeoForge 版是 {@code @Mod} 构造器里做注册与挂事件；Fabric 把它拆成
 * {@link ModInitializer}（本类，主端/服务端）与 {@link MCphoneClient}
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

    /** 手机物品。注册在 {@link #onInitialize()}。 */
    public static PhoneItem PHONE;

    @Override
    public void onInitialize() {
        // 物品：手机
        PHONE = new PhoneItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE));
        Registry.register(BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath(MODID, "phone"), PHONE);

        ModDataComponents.register();
        ModCreativeTabs.register();
        ModMenus.register();
        ModSounds.register();
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

        // 玩家进入世界时把服主那份服务端配置推给他（Fabric 没有 NeoForge 的自动同步）
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var cfg = com.november.mcphone.core.ServerConfig.allowFriendTeleport();
            var img = com.november.mcphone.core.ServerConfig.allowChatImages();
            var kb = com.november.mcphone.core.ServerConfig.chatImageMaxBytes() / 1024;
            ServerPlayNetworking.send(handler.getPlayer(), new SyncServerConfigPacket(cfg, img, kb));

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
