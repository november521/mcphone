package com.november.mcphone;

import com.november.mcphone.core.client.ClientConfig;
import com.november.mcphone.core.client.MCphoneKeyBindings;
import com.november.mcphone.core.client.PhoneKeyHandler;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.client.PhoneSession;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneContainerScreen;
import com.november.mcphone.core.menu.ModMenus;
import com.november.mcphone.feature.camera.client.CameraHandler;
import com.november.mcphone.feature.chat.client.ChatNotifier;
import com.november.mcphone.feature.chat.net.ChatClientCache;
import com.november.mcphone.feature.clock.client.PlayTime;
import com.november.mcphone.feature.music.client.DiscBayScreen;
import com.november.mcphone.feature.music.client.DiscClientCache;
import com.november.mcphone.feature.music.client.NetSongPlayback;
import com.november.mcphone.feature.music.client.MusicController;
import com.november.mcphone.feature.music.client.playback.LocalPlayback;
import com.november.mcphone.feature.notes.net.NotesClientCache;
import com.november.mcphone.feature.settings.client.WallpaperStore;
import com.november.mcphone.feature.store.net.StoreClientCache;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * MCphone 的客户端入口。
 *
 * 入口形状与 NeoForge 那一支【完全不同】，别照抄
 *
 * 那边用的是 @Mod(value = MODID, dist = Dist.CLIENT) —— 一个只在客户端加载的
 * 第二个 mod 类，由加载器保证它在专用服务端上根本不会被读到。
 * 1.20.1 的 @Mod 【没有 dist 参数】，这条路走不通。
 *
 * 这边的做法是：本类由 MCphone 通过 DistExecutor 在客户端侧调 init 挂上去。
 * 关键在于 MCphone 里【不能出现对本类的直接引用】——那会让专用服务端在加载
 * MCphone 时连带解析本类，而本类引用着 Minecraft、Screen 这些客户端类型，
 * 当场 NoClassDefFoundError。DistExecutor 收的是 Supplier 的 Supplier，
 * 正是为了把那次引用推迟到确认在客户端之后。
 *
 * 还没接进来的只剩一样
 *
 * 配置界面：那边挂的是 NeoForge 自带的 ConfigurationScreen，Forge 1.20.1
 * 【没有内置的】，要自己写一整套。见 docs/PORTING.md。
 *
 * 其余的监听器都在了 —— 相机、唱片仓界面、网络歌曲、聊天提醒、按键、
 * 进出世界的清理，与那一支一一对应。
 */
@Mod.EventBusSubscriber(modid = MCphone.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MCphoneClient {

    private MCphoneClient() {}

    /**
     * 由 MCphone 构造函数经 DistExecutor 调用，只在客户端。
     *
     * 收的是 context 而不是只收 IEventBus：注册配置要用它【实例上】的
     * registerConfig。别图省事写成静态的 ModLoadingContext.get() ——
     * 那个静态方法在 Forge 47.4 上已经标了 forRemoval，编译会告警，
     * 将来会直接没有。这与 MCphone 类注释里说的是同一件事，网上绝大多数
     * 1.20.1 教程都还是老写法。
     */
    public static void init(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();

        modBus.addListener(ClientConfig::onLoad);
        modBus.addListener(ClientConfig::onReload);
        context.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);

        modBus.addListener(MCphoneKeyBindings::register);

        MinecraftForge.EVENT_BUS.addListener(PhoneKeyHandler::onClientTick);

        MinecraftForge.EVENT_BUS.addListener(CameraHandler::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(CameraHandler::onRenderGui);
        MinecraftForge.EVENT_BUS.addListener(CameraHandler::onScreenOpening);
        // 这三个是这一支特有的，NeoForge 那边不需要，理由见 CameraHandler 类注释
        MinecraftForge.EVENT_BUS.addListener(CameraHandler::onRenderTickStart);
        MinecraftForge.EVENT_BUS.addListener(CameraHandler::onRenderTickEnd);
        MinecraftForge.EVENT_BUS.addListener(CameraHandler::onRenderGuiOverlayPre);

        // 每 tick 泵一次音频流；没在放的时候第一行就返回
        MinecraftForge.EVENT_BUS.addListener(LocalPlayback::onClientTick);

        // 一首停下来时带停止原因通知控制器，见 LocalPlayback.Ending
        LocalPlayback.setEndListener(MusicController::onTrackEnded);

        // 退出世界时清掉这个存档/服务器的状态。不清的话，下一个世界会先闪出
        // 上一个的数据，音乐还在放，OpenAL 设备句柄也会漏
        MinecraftForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingOut event) -> {
                    ChatClientCache.clear();
                    NotesClientCache.clear();
                    StoreClientCache.clear();
                    PhoneScreenRegistry.unloadWorld();

                    PlayTime.onWorldLeave();

                    LocalPlayback.shutdown();
                    DiscClientCache.clear();

                    NetSongPlayback.clear();
                });

        // 安装状态按存档存，只能在进世界时读——客户端启动时还不知道玩家要进哪个世界
        MinecraftForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingIn event) -> {
                    PhoneScreenRegistry.loadForCurrentWorld();

                    // 手机停在哪一页是上个服务器的事，这里从主屏重新开。
                    // 清在【进】世界而不是退出世界：断线时 LoggingOut 先到，
                    // 之后手机界面才被顶掉，那一下 removed() 会把页面再记一笔——
                    // 退出时清等于没清
                    PhoneSession.clear();

                    StoreClientCache.request();

                    PlayTime.onWorldJoin();
                });

        // 走监听器而不是让网络层直接调：网络层在专用服务器上也会加载，碰不得客户端的类
        StoreClientCache.setSyncListener(PhoneScreenRegistry::enforcePurchases);

        ChatClientCache.setMessageListener(ChatNotifier::onMessage);
    }

    /** 资源重载时清空换肤贴图的探测缓存，否则 F3+T 或换资源包后画的还是旧贴图，且不报错。 */
    @SubscribeEvent
    static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> PhoneSkin.clearCache());
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // 把菜单类型与界面类绑定。不注册的话，服务端 openMenu 之后客户端
        // 什么都不显示，而且【不报错】。
        //
        // 1.21.1 那边有专门的 RegisterMenuScreensEvent；1.20.1 没有，只能在
        // FMLClientSetupEvent 里手调 MenuScreens.register。必须包在
        // enqueueWork 里：MenuScreens 的注册表不是线程安全的，而
        // FMLClientSetupEvent 是【并行】派发的
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.ENDER_CHEST.get(), PhoneContainerScreen::new);
            MenuScreens.register(ModMenus.DISC_BAY.get(), DiscBayScreen::new);
        });

        // 必须在 App 目录构建之前：BrowserApp 登记时会问后端在不在。
        // 不进 enqueueWork：它只做一次 ModList 查询与一次赋值，碰的全是自家
        // 的静态字段，不像 MenuScreens 那样有别人也在写的注册表
        com.november.mcphone.feature.browser.client.BrowserBackends.installDefault();

        WallpaperStore.scan();

        // 触发 PhoneScreenRegistry 延迟加载（SPI）
        PhoneScreenRegistry.getAppCount();

        MCphone.LOGGER.info("[MCphone] 客户端加载完成，已登记 {} 个 App",
                PhoneScreenRegistry.getAppCount());
    }
}
