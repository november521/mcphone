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
            com.november.mcphone.core.script.server.economy.EconomyRuntime.start(server);
            com.november.mcphone.core.script.server.ScriptWorkers.start();
        });

        // 脚本 worker 的生死跟着服务器走（§15.5）。【停必须有】：单人游戏里服务器会在同一个
        // JVM 里停掉再起来，不关的话线程池连同排队中的求值会带着上一个世界的引用活到下一个
        // 世界，而那些求值回调到主线程时拿到的是一个已经死掉的 MinecraftServer。
        // setDaemon(true) 是"万一这里漏了别挂住 JVM"的兜底，不是关闭方案本身
        // 货币网关先关、再停 worker：worker 可能正等着主线程替它执行一笔货币调用，
        // 反过来主线程就要白等到 worker 超时（见 CurrencyGateway.close）
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            com.november.mcphone.core.script.server.economy.EconomyRuntime.stop();
            com.november.mcphone.core.script.server.ScriptWorkers.stop();
            com.november.mcphone.core.script.net.ScriptRpcHandler.clear();
        });

        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> com.november.mcphone.core.script.server.economy.EconomyCommand.register(dispatcher));

        // 手机替卡槽里的终端供电。漏了它的症状是"终端在手机里会没电"，见 TerminalCharger。
        // Fabric 没有 NeoForge 的按玩家 tick 事件，用服务端 tick 自己发
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // 超时托管每 5 分钟扫一次（见 EconomyRuntime.tick）
            com.november.mcphone.core.script.server.economy.EconomyRuntime.tick();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                com.november.mcphone.feature.terminal.TerminalCharger.onPlayerTick(player);
            }
            // 与另两个平台同形：扇出不是一个内联块，而是一个方法，隔离写在那一处
            tickDiscLoop(server.getPlayerList().getPlayers());
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

    /** 接续唱片仓单曲循环；状态变化后立刻同步本轮的新终点。 */
    private static void tickDiscLoop(Iterable<net.minecraft.server.level.ServerPlayer> players) {
        for (net.minecraft.server.level.ServerPlayer player : players) {
            try {
                if (com.november.mcphone.feature.music.DiscService.tickLoop(player)) {
                    com.november.mcphone.feature.music.DiscService.syncState(player);
                }
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                // 与 C2S 的 handleSafely 同一个理由，而这里是【每 tick × 每个玩家】的扇出：
                // 一个人身上出的事（例如仓里有个会让 JukeboxSong.fromStack 抛的物品栈）不隔离的话，
                // 排在他后面的玩家这一 tick 全被跳过，而且每 tick 复现 —— 是跨玩家的影响。
                LOGGER.error("[MCphone] 唱片循环的每 tick 扇出失败 player={}", player.getUUID(), failure);
            }
        }
    }

    /** 本模组版本号，如 "1.0.0"。onInitialize 前调用会得到空串。 */
    public static String getVersion() {
        return version;
    }
}
