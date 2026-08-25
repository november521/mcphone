package com.november.mcphone;

import com.november.mcphone.core.client.ClientConfig;
import com.november.mcphone.core.client.MCphoneKeyBindings;
import com.november.mcphone.core.client.PhoneContainerScreen;
import com.november.mcphone.core.client.PhoneKeyHandler;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.client.PhoneSession;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.menu.ModMenus;
import com.november.mcphone.feature.camera.client.CameraHandler;
import com.november.mcphone.feature.chat.client.ChatNotifier;
import com.november.mcphone.feature.chat.net.ChatClientCache;
import com.november.mcphone.feature.music.client.DiscBayScreen;
import com.november.mcphone.feature.music.client.DiscClientCache;
import com.november.mcphone.feature.music.client.NetSongPlayback;
import com.november.mcphone.feature.music.client.MusicController;
import com.november.mcphone.feature.music.client.playback.LocalPlayback;
import com.november.mcphone.feature.notes.net.NotesClientCache;
import com.november.mcphone.feature.settings.client.WallpaperStore;
import com.november.mcphone.feature.store.net.StoreClientCache;
import com.november.mcphone.feature.clock.client.PlayTime;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = MCphone.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = MCphone.MODID, value = Dist.CLIENT)
public class MCphoneClient {

    public MCphoneClient(ModContainer container, IEventBus modEventBus) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        modEventBus.addListener(ClientConfig::onLoad);
        modEventBus.addListener(ClientConfig::onReload);
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);

        modEventBus.addListener(MCphoneKeyBindings::register);

        NeoForge.EVENT_BUS.addListener(CameraHandler::onClientTick);

        NeoForge.EVENT_BUS.addListener(PhoneKeyHandler::onClientTick);

        // 每 tick 泵一次音频流；没在放的时候第一行就返回
        NeoForge.EVENT_BUS.addListener(LocalPlayback::onClientTick);

        // 一首停下来时带停止原因通知控制器，见 LocalPlayback.Ending
        LocalPlayback.setEndListener(MusicController::onTrackEnded);
        NeoForge.EVENT_BUS.addListener(CameraHandler::onRenderGui);
        NeoForge.EVENT_BUS.addListener(CameraHandler::onScreenOpening);

        // 退出世界时清掉这个存档/服务器的状态。不清的话，下一个世界会先闪出上一个的数据，
        // 音乐还在放，OpenAL 设备句柄也会漏
        NeoForge.EVENT_BUS.addListener(
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
        NeoForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingIn event) -> {
                    PhoneScreenRegistry.loadForCurrentWorld();
                    StoreClientCache.request();

                    // 手机停在哪一页是上个服务器的事，这里从主屏重新开。
                    // 清在【进】世界而不是退出世界：断线时 LoggingOut 先到，
                    // 之后手机界面才被顶掉，那一下 removed() 会把页面再记一笔——
                    // 退出时清等于没清
                    PhoneSession.clear();

                    PlayTime.onWorldJoin();
                });

        // 这两处走监听器而不是让网络层直接调：网络层在专用服务器上也会加载，碰不得客户端的类
        StoreClientCache.setSyncListener(
                PhoneScreenRegistry::enforcePurchases);

        ChatClientCache.setMessageListener(ChatNotifier::onMessage);
    }

    /** 资源重载时清空换肤贴图的探测缓存，否则 F3+T 或换资源包后画的还是旧贴图，且不报错。 */
    @SubscribeEvent
    static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> PhoneSkin.clearCache());
    }

    /** 把菜单类型与界面类绑定。不注册的话，服务端 openMenu 后客户端什么都不显示。 */
    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.ENDER_CHEST.get(), PhoneContainerScreen::new);
        event.register(ModMenus.DISC_BAY.get(), DiscBayScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // 必须在 App 目录构建之前：BrowserApp 登记时会问后端在不在
        com.november.mcphone.feature.browser.client.BrowserBackends.installDefault();

        WallpaperStore.scan();

        // 触发 PhoneScreenRegistry 延迟加载（内建 + SPI）
        PhoneScreenRegistry.getAppCount();

        MCphone.LOGGER.info("MCphone 客户端加载完成");
        MCphone.LOGGER.info("玩家: {}", Minecraft.getInstance().getUser().getName());
    }
}
