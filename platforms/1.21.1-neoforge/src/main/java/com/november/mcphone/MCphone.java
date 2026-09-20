package com.november.mcphone;

import com.mojang.logging.LogUtils;
import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.core.ModCreativeTabs;
import com.november.mcphone.core.ModDataComponents;
import com.november.mcphone.core.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(MCphone.MODID)
public class MCphone {

    public static final String MODID = "mcphone";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 运行时的真实版本号，tooltip 用它填 %s，语言文件里不写死。
     * 在构造函数里由 modContainer 赋值，不用 ModList 静态查询——那依赖 FML 的类加载时序，取不到时是静默的空值。
     */
    private static String version = "";

    public MCphone(IEventBus modEventBus, ModContainer modContainer) {
        ModItems.ITEMS.register(modEventBus);
        ModDataComponents.COMPONENTS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        com.november.mcphone.core.menu.ModMenus.MENUS.register(modEventBus);
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);
        com.november.mcphone.core.ModSounds.SOUND_EVENTS.register(modEventBus);
        modEventBus.addListener(com.november.mcphone.core.net.NetworkHandler::register);

        // 游戏总线，显式挂载：这三条漏了没有任何症状，只是下线玩家的表再也不缩小
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                com.november.mcphone.core.net.RequestThrottle::onPlayerLoggedOut);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                com.november.mcphone.feature.music.DiscService::onPlayerLoggedOut);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                com.november.mcphone.feature.chat.ChatImageUploads::onPlayerLoggedOut);

        // 手机替卡槽里的终端供电。漏了它的症状是"终端在手机里会没电"，见 TerminalCharger
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                com.november.mcphone.feature.terminal.TerminalCharger::onPlayerTick);

        // 开服时清掉没有消息认领的图片文件，理由见 ChatImageStore.sweepOrphans
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                com.november.mcphone.feature.chat.ChatImageStore::onServerStarted);

        // 脚本 worker 的生死跟着服务器走（§15.5）。【停必须有】：单人游戏里服务器会在同一个
        // JVM 里停掉再起来，不关的话线程池连同排队中的求值会带着上一个世界的引用活到下一个
        // 世界，而那些求值回调到主线程时拿到的是一个已经死掉的 MinecraftServer。
        // setDaemon(true) 是"万一这里漏了别挂住 JVM"的兜底，不是关闭方案本身
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStartedEvent e) -> {
                    com.november.mcphone.core.script.server.economy.EconomyRuntime.start(e.getServer());
                    com.november.mcphone.core.script.server.ScriptWorkers.start();
                });
        // 货币网关先关、再停 worker：worker 可能正等着主线程替它执行一笔货币调用，
        // 反过来主线程就要白等到 worker 超时（见 CurrencyGateway.close）
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> {
                    com.november.mcphone.core.script.server.economy.EconomyRuntime.stop();
                    com.november.mcphone.core.script.server.ScriptWorkers.stop();
                    com.november.mcphone.core.script.net.ScriptRpcHandler.clear();
                });
        // 超时托管每 5 分钟扫一次（见 EconomyRuntime.tick）
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) -> com.november.mcphone.core.script.server.economy.EconomyRuntime.tick());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) ->
                        tickDiscLoop(e.getServer().getPlayerList().getPlayers()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.RegisterCommandsEvent e) -> com.november.mcphone.core.script.server.economy.EconomyCommand.register(e.getDispatcher()));

        // SERVER 而非 COMMON：必须由服主一份说了算，且 NeoForge 会同步给客户端供界面藏按钮
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,
                com.november.mcphone.core.ServerConfig.SPEC, "mcphone-server.toml");

        // 「终端」App 接哪几家存储模组，setup 阶段才点数 —— 那时所有模组都构造完了。
        // 为什么不能更早，见 Terminals.onCommonSetup
        modEventBus.addListener(
                com.november.mcphone.feature.terminal.integration.Terminals::onCommonSetup);

        // 放在自家注册之后：兼容模块可能要看我们已经注册了什么
        com.november.mcphone.compat.CompatModules.init(modEventBus);

        version = modContainer.getModInfo().getVersion().toString();

        LOGGER.info("MCphone 模组加载完成 —— 手机已就绪");
    }

    /** 接续唱片仓单曲循环；状态变化后立刻同步本轮的新终点。 */
    private static void tickDiscLoop(Iterable<net.minecraft.server.level.ServerPlayer> players) {
        for (net.minecraft.server.level.ServerPlayer player : players) {
            if (com.november.mcphone.feature.music.DiscService.tickLoop(player)) {
                com.november.mcphone.feature.music.DiscService.syncState(player);
            }
        }
    }

    /** 本模组版本号，如 "1.0.0"。模组构造前调用会得到空串。 */
    public static String getVersion() {
        return version;
    }
}
