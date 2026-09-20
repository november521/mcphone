package com.november.mcphone;

import com.november.mcphone.core.ServerConfig;
import com.november.mcphone.core.client.ClientConfig;
import com.november.mcphone.core.client.MCphoneKeyBindings;
import com.november.mcphone.core.client.PhoneHud;
import com.november.mcphone.core.client.PhoneContainerScreen;
import com.november.mcphone.core.client.PhoneKeyHandler;
import com.november.mcphone.core.client.PhoneScreenOnSync;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.script.client.LocalScriptSource;
import com.november.mcphone.core.client.PhoneSession;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.menu.ModMenus;
import com.november.mcphone.core.script.client.tex.AppTextures;
import com.november.mcphone.feature.camera.client.CameraFlash;
import com.november.mcphone.feature.camera.client.CameraHandler;
import com.november.mcphone.feature.camera.client.CameraMode;
import com.november.mcphone.feature.chat.client.ChatImageCache;
import com.november.mcphone.feature.chat.client.ChatImageSender;
import com.november.mcphone.feature.chat.client.ChatNotifier;
import com.november.mcphone.feature.chat.net.ChatClientCache;
import com.november.mcphone.feature.music.client.DiscBayScreen;
import com.november.mcphone.feature.music.client.DiscClientCache;
import com.november.mcphone.feature.music.client.MusicController;
import com.november.mcphone.feature.music.client.NetSongPlayback;
import com.november.mcphone.feature.music.client.playback.LocalPlayback;
import com.november.mcphone.feature.notes.net.NotesClientCache;
import com.november.mcphone.feature.settings.client.WallpaperStore;
import com.november.mcphone.feature.store.net.StoreClientCache;
import com.november.mcphone.feature.clock.client.PlayTime;
import com.november.mcphone.feature.terminal.client.TerminalSlotScreen;
import com.november.mcphone.platform.client.ClientTicks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 客户端入口。原 NeoForge 版是一个 {@code @Mod(dist = CLIENT)} 构造器 + 一串
 * {@code @SubscribeEvent}；这里把同一张接线表按 Fabric 的事件源重排：
 * 「每 tick」统一走 {@link ClientTicks} 门面，「进/出世界」走
 * ClientPlayConnectionEvents，「渲染 HUD」走 HudRenderCallback，
 * 「打开界面」走 ScreenEvents。
 */
public class MCphoneClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientConfig.load();

        // S17 Stage 2：接住服务端握手（serverId / epoch / 部署表）。必须在进服之前装好 ——
        // 握手是登录时推的，晚了就丢了（丢了的表现是 connectionEpoch 恒 0，请求判过期连接）
        com.november.mcphone.core.script.client.ClientHandshake.install();

        MCphoneKeyBindings.register();

        // 物品模型属性（离手摆姿、3D 亮屏那些）。要在资源加载前就位
        com.november.mcphone.core.client.PhoneItemProperties.register();

        PhoneHud.registerLayer();

        // 共用侧每 tick 要做的事，全在 ClientTicks 里排队 —— 共用侧再加功能这里一行不用改
        ClientTicks.onEndTick(com.november.mcphone.core.client.ClientTicks::tick);
        ClientTicks.onEndTick(PhoneKeyHandler::tick);
        ClientTicks.onEndTick(PhoneHud::onClientTick);
        ClientTicks.onEndTick(com.november.mcphone.feature.reader.client.ReaderKeyHandler::tick);
        ClientTicks.onEndTick(LocalPlayback::tick);
        ClientTicks.onEndTick(ChatImageSender::tick);
        // 快门与退出键由 CameraHandler 在 tick 末尾消费。只注册下面的 HUD 回调会让
        // 按键设置里看得到 V / X，却没有任何代码读取按下次数，两个键因而完全失效。
        ClientTicks.onEndTick(CameraHandler::onClientTick);

        // 一首停下来时带停止原因通知控制器，见 LocalPlayback.Ending
        LocalPlayback.setEndListener(MusicController::onTrackEnded);

        // 相机 App：取景画在 HUD 上；打开任意界面就退出相机模式，
        // 否则玩家会卡在没有 HUD 的状态里
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(
                CameraHandler::onRenderGui);
        ScreenEvents.BEFORE_INIT.register((client, screen, w, h) -> CameraMode.exit());

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // 排在最前：它要在下面那些缓存被清掉之前把会话存下来。
            // 这条路上不能碰 setScreen，理由见 PhoneHud.onWorldLeave
            PhoneHud.onWorldLeave();
            PhoneScreenOnSync.forget();
            // S17 Stage 2：断线清掉握手状态（serverId/epoch/部署表），下次进服重新握
            com.november.mcphone.core.script.client.ClientHandshake.clear();

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

            // Fabric 侧追加：清掉上一个服务器的配置同步态
            ServerConfig.clearSync();
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // 【必须在 loadForCurrentWorld 之前】：玩家放进 mcphone/apps/ 的脚本 App 是动态登记的，
            // 而那一句读存档时只认目录里已有的 id，读完还会按当前目录覆写存档 —— 晚一步，
            // 重启后已装的脚本 App 会从主屏消失，并且被从存档里抹掉
            LocalScriptSource.registerAll();
            PhoneScreenRegistry.loadForCurrentWorld();
            StoreClientCache.request();

            PhoneSession.clearAll();

            PlayTime.onWorldJoin();
        });

        // S2C 的接收器注册：共享阶段只登记编解码，这里（确认在客户端）才真正挂上
        com.november.mcphone.core.client.ClientNetworking.register();

        // 皮肤、App 包里的图片与相机闪光这些跟着资源包重载走。
        // 图片是 DynamicTexture，重载之后未必还在
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public ResourceLocation getFabricId() {
                        return ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "skin_reload");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager manager) {
                        PhoneSkin.clearCache();
                        AppTextures.clearCache();
                        CameraFlash.dispose();
                    }
                });

        // 把菜单类型与界面类绑定。不注册的话，服务端 openMenu 后客户端什么都不显示。
        MenuScreens.register(ModMenus.ENDER_CHEST.get(), PhoneContainerScreen::new);
        MenuScreens.register(ModMenus.DISC_BAY.get(), DiscBayScreen::new);
        MenuScreens.register(ModMenus.TERMINAL_SLOT.get(), TerminalSlotScreen::new);

        // 必须在 App 目录构建之前：BrowserApp 登记时会问后端在不在
        com.november.mcphone.feature.browser.client.BrowserBackends.installDefault();

        // 壁纸扫描不能在这儿直接做：建纹理是 GL 调用，而客户端入口点跑在 Minecraft 的
        // 构造函数里，窗口还没建、GL 上下文不存在 —— 壁纸目录里只要有一张图，
        // glGenTextures 就原生崩溃（EXCEPTION_ACCESS_VIOLATION，只有 hs_err，没有 MC
        // 崩溃报告；2026-09-10 实测）。目录空着时什么都不加载，所以这个雷一直埋到玩家
        // 第一次往 wallpapers/ 放图才炸。
        //
        // 推迟到第一个客户端 tick：那时标题屏已经在渲染，GL 必然就绪。这段留在平台侧
        // 而不是 shared/ 的 WallpaperStore 里 —— 它靠加载器的 tick 事件，而且
        // "什么时候能碰 GL"本来就是各加载器入口时机不同的事。
        ClientTicks.onEndTick(new Runnable() {
            private boolean done;

            @Override
            public void run() {
                if (done) return;
                done = true;
                WallpaperStore.scan();
            }
        });

        // 触发 PhoneScreenRegistry 延迟加载（内建 + SPI）
        PhoneScreenRegistry.getAppCount();

        // 这两处走监听器而不是让网络层直接调：网络层在专用服务器上也会加载，碰不得客户端的类
        StoreClientCache.setSyncListener(PhoneScreenRegistry::enforcePurchases);
        ChatClientCache.setMessageListener(ChatNotifier::onMessage);
        ChatClientCache.setImageListener(ChatImageCache::accept);

        MCphone.LOGGER.info("MCphone 客户端加载完成");
        MCphone.LOGGER.info("玩家: {}", Minecraft.getInstance().getUser().getName());
    }
}
