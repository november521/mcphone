package com.november.mcphone;

import com.november.mcphone.core.client.ClientConfig;
import com.november.mcphone.core.client.MCphoneKeyBindings;
import com.november.mcphone.core.client.PhoneContainerScreen;
import com.november.mcphone.core.client.PhoneKeyHandler;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.menu.ModMenus;
import com.november.mcphone.feature.camera.client.CameraHandler;
import com.november.mcphone.feature.chat.client.ChatNotifier;
import com.november.mcphone.feature.chat.net.ChatClientCache;
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

        // 客户端配置。先挂监听再注册：两件事都在构造期完成、加载在其后，
        // 顺序其实不影响，但反过来读着像"注册完才想起要听"
        modEventBus.addListener(ClientConfig::onLoad);
        modEventBus.addListener(ClientConfig::onReload);
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);

        // 按键注册是【模组总线】事件，显式挂载而不依赖注解自动路由
        modEventBus.addListener(MCphoneKeyBindings::register);

        // 相机模式的监听都在【游戏总线】，同样显式挂载
        NeoForge.EVENT_BUS.addListener(CameraHandler::onClientTick);

        // 快捷键开机，同样在游戏总线上读按键
        NeoForge.EVENT_BUS.addListener(PhoneKeyHandler::onClientTick);

        // 音乐要每 tick 泵一次音频流：把放完的缓冲换成新的、发现放完了收尾、
        // 跟上音量滑块的变化。没在放的时候第一行就返回，不花钱
        NeoForge.EVENT_BUS.addListener(LocalPlayback::onClientTick);
        NeoForge.EVENT_BUS.addListener(CameraHandler::onRenderGui);
        NeoForge.EVENT_BUS.addListener(CameraHandler::onScreenOpening);

        // 退出世界时清空聊天缓存。不清的话，换到另一个服务器时会先闪出
        // 上一个服务器的会话列表——那是别处的数据，既尴尬又可能泄露信息
        NeoForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingOut event) -> {
                    ChatClientCache.clear();
                    NotesClientCache.clear();
                    StoreClientCache.clear();
                    // 安装状态是按存档存的，退出时必须一并卸下。留着的话，
                    // 下一个存档在自己的状态读进来之前会先显示上一个的主屏，
                    // 玩家若恰好这时点了什么，还会被写进新存档的文件里
                    PhoneScreenRegistry.unloadWorld();

                    // 本次游玩计时归零。不清的话，回主菜单再进另一个存档，
                    // "本次"会从上一个世界就开始算
                    PlayTime.onWorldLeave();

                    // 音乐停下并把音频设备还给系统。不还的话每次进出世界
                    // 都漏一个 OpenAL 设备句柄，而且上一个世界的歌会一直放
                    LocalPlayback.shutdown();
                });

        // 进世界时读这个存档自己的安装状态。不能在客户端启动时读——那会儿
        // 还不知道玩家要进哪个世界
        NeoForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingIn event) -> {
                    PhoneScreenRegistry.loadForCurrentWorld();
                    // 顺手要一份购买记录：没买过的付费 App 要从主屏摘掉，
                    // 而那要等这份记录到了才知道
                    StoreClientCache.request();

                    // 本次游玩从这一刻起算，顺便向服务端要一份统计，
                    // 好知道这个存档一共玩了多久
                    PlayTime.onWorldJoin();
                });

        // 购买记录一到就核对主屏。走监听器而不是让网络层直接调注册表：
        // 那个类现在含客户端类型，网络层两端都会加载，碰不得
        StoreClientCache.setSyncListener(
                PhoneScreenRegistry::enforcePurchases);

        // 收到消息时弹通知。装在这里而不是让网络层直接调 ChatNotifier：
        // 网络层在专用服务器上也会加载，碰不得客户端的类
        ChatClientCache.setMessageListener(ChatNotifier::onMessage);
    }

    /**
     * 资源重载时清空换肤贴图的探测缓存。
     *
     * 不清的话，玩家按 F3+T 重载资源包、或换了资源包之后，界面画的还是
     * 上一套贴图——而且不报错，只是"改了没反应"，很难想到是缓存。
     */
    @SubscribeEvent
    static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> PhoneSkin.clearCache());
    }

    /**
     * 把菜单类型与界面类绑定。
     *
     * RegisterMenuScreensEvent 是【模组总线】事件。不注册的话，服务端
     * openMenu 后客户端不知道该开哪个界面，表现为菜单开了但什么都没显示。
     */
    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.ENDER_CHEST.get(), PhoneContainerScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // 装了 MCEF 就把浏览器后端接上。必须在 App 目录构建【之前】：
        // BrowserApp 登记时会问后端在不在
        com.november.mcphone.feature.browser.client.BrowserBackends.installDefault();

        // 扫描壁纸目录
        WallpaperStore.scan();

        // 触发 PhoneScreenRegistry 延迟加载（内建 + SPI）
        PhoneScreenRegistry.getAppCount();

        MCphone.LOGGER.info("MCphone 客户端加载完成");
        MCphone.LOGGER.info("玩家: {}", Minecraft.getInstance().getUser().getName());
    }
}
