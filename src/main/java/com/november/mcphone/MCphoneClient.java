package com.november.mcphone;

import com.november.mcphone.core.ServerConfig;
import com.november.mcphone.core.client.AppHotkeyHandler;
import com.november.mcphone.core.client.ClientConfig;
import com.november.mcphone.core.client.MCphoneKeyBindings;
import com.november.mcphone.core.client.PhoneContainerScreen;
import com.november.mcphone.core.client.PhoneKeyHandler;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.client.PhoneSession;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.menu.ModMenus;
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
import com.november.mcphone.feature.store.client.StoreClientCache;
import com.november.mcphone.feature.clock.client.PlayTime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * MCphone 的 Fabric 客户端入口。
 *
 * 原 NeoForge 版是 {@code @Mod(dist=CLIENT)} + {@code @EventBusSubscriber}，
 * 构造函数里挂各种事件总线监听；Fabric 把这一切收进
 * {@link ClientModInitializer#onInitializeClient()}。
 *
 * 按键的键盘事件（App 快捷键）走 {@link KeyboardHandlerMixin} 转发，不用 tick 轮询。
 */
public class MCphoneClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 客户端配置 → FontPalette / 播放器 / 快捷键表 / 快门闪光
        ClientConfig.load();

        // 三个 KeyMapping（拍照/退出相机/开机）注册进原版按键设置
        MCphoneKeyBindings.register();

        // 每 tick 的监听（相机、开机键、音频泵、发图队列）
        ClientTickEvents.END_CLIENT_TICK.register(mc -> CameraHandler.onClientTick());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> PhoneKeyHandler.onClientTick());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> LocalPlayback.onClientTick());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> ChatImageSender.onClientTick());

        // HUD 渲染（相机取景框）
        HudRenderCallback.EVENT.register(CameraHandler::onRenderGui);

        // 安全网：打开任意界面就退出相机模式，否则玩家会卡在没有 HUD 的状态里
        ScreenEvents.BEFORE_INIT.register((client, screen, w, h) -> CameraMode.exit());

        // 退出世界时清掉这个存档/服务器的状态。不清的话，下一个世界会先闪出
        // 上一个的数据，音乐还在放，OpenAL 设备句柄也会漏
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
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

            ServerConfig.clearSync();
        });

        // 安装状态按存档存，只能在进世界时读——客户端启动时还不知道玩家要进哪个世界
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            PhoneScreenRegistry.loadForCurrentWorld();
            StoreClientCache.request();

            // 手机停在哪一页是上个服务器的事，这里从主屏重新开
            PhoneSession.clear();

            PlayTime.onWorldJoin();
        });

        // S2C 网络包注册 + 客户端接收器
        com.november.mcphone.core.client.ClientNetworking.register();

        // 资源重载时清空换肤贴图的探测缓存，否则 F3+T 或换资源包后画的还是旧贴图。
        // 相机那条模糊后处理链一并扔掉：着色器程序跟着资源走，重载之后旧的那份
        // 要么黑屏要么直接崩。下次拍照时会重新建一条。
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public ResourceLocation getFabricId() {
                        return ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "skin_reload");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager manager) {
                        PhoneSkin.clearCache();
                        CameraFlash.dispose();
                    }
                });

        // 把菜单类型与界面类绑定。不注册的话，服务端 openMenu 后客户端什么都不显示。
        MenuScreens.register(ModMenus.ENDER_CHEST, PhoneContainerScreen::new);
        MenuScreens.register(ModMenus.DISC_BAY, DiscBayScreen::new);

        // 必须在 App 目录构建之前：BrowserApp 登记时会问后端在不在
        com.november.mcphone.feature.browser.client.BrowserBackends.installDefault();

        WallpaperStore.scan();

        // 触发 PhoneScreenRegistry 延迟加载（内建 + SPI）
        PhoneScreenRegistry.getAppCount();

        // 这两处走监听器而不是让网络层直接调：网络层在专用服务器上也会加载，碰不得客户端的类
        StoreClientCache.setSyncListener(PhoneScreenRegistry::enforcePurchases);
        ChatClientCache.setMessageListener(ChatNotifier::onMessage);
        ChatClientCache.setImageListener(ChatImageCache::accept);

        LocalPlayback.setEndListener(MusicController::onTrackEnded);

        // 收尾（原有按键输入监听由 mixin 转发，无需在此挂）
        MCphone.LOGGER.info("MCphone 客户端加载完成");
        MCphone.LOGGER.info("玩家: {}", Minecraft.getInstance().getUser().getName());
    }
}
