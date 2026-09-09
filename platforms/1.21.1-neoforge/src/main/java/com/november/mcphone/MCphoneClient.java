package com.november.mcphone;

import com.november.mcphone.core.client.AppHotkeyHandler;
import com.november.mcphone.core.client.ClientConfig;
import com.november.mcphone.core.client.MCphoneKeyBindings;
import com.november.mcphone.core.client.PhoneHud;
import com.november.mcphone.core.client.PhoneContainerScreen;
import com.november.mcphone.core.client.PhoneKeyHandler;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.client.PhoneSession;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.menu.ModMenus;
import com.november.mcphone.feature.camera.client.CameraFlash;
import com.november.mcphone.feature.camera.client.CameraHandler;
import com.november.mcphone.feature.chat.client.ChatImageCache;
import com.november.mcphone.feature.chat.client.ChatImageSender;
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
import com.november.mcphone.feature.terminal.client.TerminalSlotScreen;
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

        // 副手 HUD 那一层。插在原版战利品条之后：玩法 HUD 之上，F3 与聊天框之下
        modEventBus.addListener(PhoneHud::onRegisterLayers);

        NeoForge.EVENT_BUS.addListener(CameraHandler::onClientTick);

        NeoForge.EVENT_BUS.addListener(PhoneKeyHandler::onClientTick);

        // 手机进出副手、Alt 按下松开都在这条 tick 里判，见 PhoneHud
        NeoForge.EVENT_BUS.addListener(PhoneHud::onClientTick);


        // 每个 App 自己的快捷键。它不是 KeyMapping，只能听按下事件，理由见 AppHotkeys。
        // 鼠标键单独一条：那类事件与键盘的不是同一个类，而且它可以取消
        NeoForge.EVENT_BUS.addListener(AppHotkeyHandler::onKeyInput);
        NeoForge.EVENT_BUS.addListener(AppHotkeyHandler::onMouseInput);

        // 每 tick 泵一次音频流；没在放的时候第一行就返回
        NeoForge.EVENT_BUS.addListener(LocalPlayback::onClientTick);

        // 冷却期里点的那几张图排着，每 tick 看一眼闸开了没有；队伍空的时候第一行就返回
        NeoForge.EVENT_BUS.addListener(ChatImageSender::onClientTick);

        // 一首停下来时带停止原因通知控制器，见 LocalPlayback.Ending
        LocalPlayback.setEndListener(MusicController::onTrackEnded);
        NeoForge.EVENT_BUS.addListener(CameraHandler::onRenderGui);
        NeoForge.EVENT_BUS.addListener(CameraHandler::onScreenOpening);

        // 退出世界时清掉这个存档/服务器的状态。不清的话，下一个世界会先闪出上一个的数据，
        // 音乐还在放，OpenAL 设备句柄也会漏
        NeoForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingOut event) -> {
                    // 排在最前：它要在下面那些缓存被清掉之前把会话存下来。
                    // 这条路上不能碰 setScreen，理由见 PhoneHud.onWorldLeave
                    PhoneHud.onWorldLeave();
                    com.november.mcphone.core.client.PhoneScreenOnSync.forget();

                    ChatClientCache.clear();
                    ChatImageCache.clear();
                    ChatImageSender.clear();
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
        ChatClientCache.setImageListener(ChatImageCache::accept);
    }

    /**
     * 资源重载时清空换肤贴图的探测缓存，否则 F3+T 或换资源包后画的还是旧贴图，且不报错。
     *
     * 相机那条模糊后处理链一并扔掉：着色器程序跟着资源走，重载之后旧的那份要么黑屏
     * 要么直接崩，而且同样不报错。下次拍照时会重新建一条。
     */
    @SubscribeEvent
    static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            PhoneSkin.clearCache();
            CameraFlash.dispose();
        });
    }

    /** 把菜单类型与界面类绑定。不注册的话，服务端 openMenu 后客户端什么都不显示。 */
    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.ENDER_CHEST.get(), PhoneContainerScreen::new);
        event.register(ModMenus.DISC_BAY.get(), DiscBayScreen::new);
        event.register(ModMenus.TERMINAL_SLOT.get(), TerminalSlotScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // 手机物品的黑屏/白屏切换。enqueueWork 是必须的：ItemProperties 后面是普通 HashMap，
        // 而这个事件和别的模组并行跑，见 PhoneItemProperties
        event.enqueueWork(com.november.mcphone.core.client.PhoneItemProperties::register);

        // 必须在 App 目录构建之前：BrowserApp 登记时会问后端在不在
        com.november.mcphone.feature.browser.client.BrowserBackends.installDefault();

        WallpaperStore.scan();

        // 触发 PhoneScreenRegistry 延迟加载（内建 + SPI）
        PhoneScreenRegistry.getAppCount();

        MCphone.LOGGER.info("MCphone 客户端加载完成");
        MCphone.LOGGER.info("玩家: {}", Minecraft.getInstance().getUser().getName());
    }
}
